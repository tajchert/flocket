#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
# Runs flocket-share against a real, isolated hc session (flocket-test-*).
# Usage: sh protocol/tools/test-flocket-share.sh
# Skips (exit 0) when hc or python3 is missing. Always stops the session it started.

here=$(cd -- "$(dirname -- "$0")" && pwd)
HELPER=$here/flocket-share

command -v hc >/dev/null 2>&1 || { echo "skip: hc not found"; exit 0; }
command -v python3 >/dev/null 2>&1 || { echo "skip: python3 not found"; exit 0; }

export TMPDIR=/tmp
suffix=$(od -An -tx1 -N3 /dev/urandom | tr -d ' \n')
HC_SESSION=flocket-test-$suffix
export HC_SESSION
work=$(mktemp -d /tmp/flocket-share-test.XXXXXX)
cleanup() {
  hc server stop >/dev/null 2>&1
  rm -rf "$work"
}
trap cleanup EXIT
trap 'exit 130' INT TERM

pass=0
fail=0
ok() { pass=$((pass + 1)); echo "ok   $1"; }
bad() { fail=$((fail + 1)); echo "FAIL $1${2:+: $2}"; }
check() { # name, condition result (0 = pass), detail
  if [ "$2" -eq 0 ]; then ok "$1"; else bad "$1" "$3"; fi
}

rpc() { { printf '%s\n' "$1"; sleep 1; } | hc connect 2>/dev/null | head -n 1; }
getdoc() { rpc '{"jsonrpc":"2.0","id":1,"method":"doc.get","params":{"name":"flocket-share"}}'; }

# py CODE: run python3 -I with the doc reply in $DOC (stdin) and extra args.
pydoc() { python3 -I -c "import json,sys,time
r=json.load(sys.stdin)['result']; v=r['value']; ver=r['version']
$1" "$2" "$3" 2>&1; }

# seed VALUE_JSON: store a document value regardless of its current content.
seed() {
  ver=$(getdoc | pydoc 'print(ver)')
  req=$(python3 -I -c 'import json,sys
print(json.dumps({"jsonrpc":"2.0","id":1,"method":"doc.set","params":{"name":"flocket-share","version":int(sys.argv[1]),"value":json.loads(sys.argv[2])}}))' "$ver" "$1")
  rpc "$req" >/dev/null
}

hc run --bg --name t --cwd /tmp -- sleep 300 >/dev/null 2>&1 || { echo "cannot start hc session"; exit 1; }
cd "$work" || exit 1
printf 'hello' > report.png

# 1. not in a herdr terminal
env -u HC_TERMINAL_ID sh "$HELPER" report.png >/dev/null 2>&1
check "1 no HC_TERMINAL_ID exits 2" $(( $? == 2 ? 0 : 1 ))

# 2. missing path
HC_TERMINAL_ID=t1 sh "$HELPER" report.png nope.png >/dev/null 2>&1
rc=$?
ver=$(getdoc | pydoc 'print(ver)')
check "2 missing path exits 1, document untouched" $(( rc == 1 && ver == 0 ? 0 : 1 )) "rc=$rc version=$ver"

# 3. one relative path
out=$(HC_TERMINAL_ID=t1 sh "$HELPER" report.png 2>&1)
rc=$?
getdoc | pydoc 'import re
e=v["shares"][0]
assert len(v["shares"])==1 and v["v"]==1
assert e["terminal"]=="t1", e
assert e["paths"]==[sys.argv[1]], e
assert re.fullmatch("[0-9a-f]{32}", e["id"]), e
assert abs(e["at"]-time.time())<=60, e
assert "note" not in e' "$(cd "$work" && pwd -P)/report.png" >"$work/err" 2>&1
r2=$?
# the helper may report a symlink-resolved or plain path; accept both
if [ $r2 -ne 0 ]; then
  getdoc | pydoc 'e=v["shares"][0]
assert len(v["shares"])==1 and e["terminal"]=="t1" and e["paths"]==[sys.argv[1]], e' "$work/report.png" >"$work/err" 2>&1
  r2=$?
fi
check "3 one relative path" $(( rc == 0 && r2 == 0 ? 0 : 1 )) "$out $(cat "$work/err")"
case $out in "shared 1 file(s) to Flocket") ok "3b stdout message";; *) bad "3b stdout message" "$out";; esac

# 4. two runs, newest first
sleep 1
HC_TERMINAL_ID=t1 sh "$HELPER" -m second report.png >/dev/null 2>&1
getdoc | pydoc 'assert len(v["shares"])==2 and v["shares"][0].get("note")=="second" and "note" not in v["shares"][1], v' >"$work/err" 2>&1
check "4 two runs, newest first" $? "$(cat "$work/err")"

# 5. note with quote, backslash, tab
seed null
note=$(printf 'a"b\\c\td')
HC_TERMINAL_ID=t1 sh "$HELPER" -m "$note" report.png >/dev/null 2>&1
getdoc | pydoc 'assert v["shares"][0]["note"]==sys.argv[1], v' "$note" >"$work/err" 2>&1
check "5 note round-trips" $? "$(cat "$work/err")"

# 6. file name with spaces and a quote
seed null
weird="$work/my \"odd\" file.txt"
printf 'x' > "$weird"
HC_TERMINAL_ID=t1 sh "$HELPER" "$weird" >/dev/null 2>&1
getdoc | pydoc 'assert v["shares"][0]["paths"]==[sys.argv[1]], v' "$weird" >"$work/err" 2>&1
check "6 odd file name round-trips" $? "$(cat "$work/err")"

# 7. 8 parallel runs
seed null
i=0
while [ $i -lt 8 ]; do
  HC_TERMINAL_ID=t1 sh "$HELPER" -m "n$i" report.png >/dev/null 2>&1 &
  i=$((i + 1))
done
wait
getdoc | pydoc 'ids={e["id"] for e in v["shares"]}
assert len(v["shares"])==8 and len(ids)==8, len(v["shares"])
assert sorted(e["note"] for e in v["shares"])==sorted("n%d"%i for i in range(8))' >"$work/err" 2>&1
check "7 eight parallel runs" $? "$(cat "$work/err")"

# 8. oversized value is replaced by a fresh list
seed "$(python3 -I -c 'import json
print(json.dumps({"v":1,"shares":[{"id":"x","terminal":"t9","paths":["/"+"a"*71680],"at":1}]}))')"
HC_TERMINAL_ID=t1 sh "$HELPER" report.png >/dev/null 2>&1
getdoc | pydoc 'assert len(v["shares"])==1 and v["shares"][0]["terminal"]=="t1", v' >"$work/err" 2>&1
check "8 70 KiB value starts fresh" $? "$(cat "$work/err")"

# 8b. empty list gives no leading comma
seed '{"v":1,"shares":[]}'
HC_TERMINAL_ID=t1 sh "$HELPER" report.png >/dev/null 2>&1
getdoc | pydoc 'assert len(v["shares"])==1, v' >"$work/err" 2>&1
check "8b empty list" $? "$(cat "$work/err")"

# 9. foreign value is replaced
seed '{"other":1}'
HC_TERMINAL_ID=t1 sh "$HELPER" report.png >/dev/null 2>&1
getdoc | pydoc 'assert set(v)=={"v","shares"} and v["v"]==1 and len(v["shares"])==1, v' >"$work/err" 2>&1
check "9 foreign value replaced" $? "$(cat "$work/err")"

# 10. unreachable core
out=$(HC_BIN=false HC_TERMINAL_ID=t1 sh "$HELPER" report.png 2>&1 >/dev/null)
rc=$?
case $out in
  *"If you are running in a sandbox, rerun this command outside it.") hint=0 ;;
  *) hint=1 ;;
esac
check "10 HC_BIN=false exits 3 with sandbox hint" $(( rc == 3 && hint == 0 ? 0 : 1 )) "rc=$rc out=$out"

echo
if [ $fail -eq 0 ]; then echo "all $pass cases passed"; else echo "$fail failed, $pass passed"; exit 1; fi
