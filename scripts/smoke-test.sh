#!/usr/bin/env bash
# End-to-end check against a running API (default http://localhost:8080), e.g. after `docker compose up`.
# Calls every endpoint and every main error case over real HTTP and checks the status codes.
# Needs: curl, python3. Usage: scripts/smoke-test.sh [base-url]
set -euo pipefail

BASE="${1:-http://localhost:8080}"
PASS=0
FAIL=0

# check <expected-status> <description> <curl args...>; prints the response body, keeps it in $BODY
check() {
  local expected="$1" label="$2"
  shift 2
  local out status
  out=$(curl -s -w $'\n%{http_code}' -H 'Content-Type: application/json' "$@")
  status="${out##*$'\n'}"
  BODY="${out%$'\n'*}"
  if [[ "$status" == "$expected" ]]; then
    PASS=$((PASS + 1)); echo "PASS  $status  $label"
  else
    FAIL=$((FAIL + 1)); echo "FAIL  $status (expected $expected)  $label"
  fi
  echo "      $(printf '%s' "$BODY" | tr '\n' ' ' | cut -c1-160)"
}

json() { python3 -c "import sys, json; print(json.loads(sys.argv[1])$2)" "$BODY"; }

check 200 "GET /health" "$BASE/health"

check 201 "create account A" -X POST "$BASE/accounts" -d '{"name":"Alice"}'
A=$(json "$BODY" "['id']")
check 201 "create account B" -X POST "$BASE/accounts" -d '{"name":"Bob"}'
B=$(json "$BODY" "['id']")
check 400 "client can't set balance" -X POST "$BASE/accounts" -d '{"name":"Eve","balance":500}'
check 400 "blank name" -X POST "$BASE/accounts" -d '{"name":"   "}'
check 400 "malformed JSON" -X POST "$BASE/accounts" -d '{not json'

check 201 "credit 100 to A" -X POST "$BASE/accounts/$A/transactions" -d '{"type":"credit","amount":100,"reference_id":"inv-1"}'
check 409 "same reference_id again -> duplicate, original returned" -X POST "$BASE/accounts/$A/transactions" -d '{"type":"credit","amount":100,"reference_id":"inv-1"}'
check 201 "credit 50 to A" -X POST "$BASE/accounts/$A/transactions" -d '{"type":"credit","amount":"50.00","reference_id":"inv-2"}'
check 201 "debit 30 from A" -X POST "$BASE/accounts/$A/transactions" -d '{"type":"debit","amount":30,"reference_id":"pay-1"}'
check 201 "debit 20 from A" -X POST "$BASE/accounts/$A/transactions" -d '{"type":"debit","amount":20,"reference_id":"pay-2"}'
check 422 "debit 1000 -> insufficient balance" -X POST "$BASE/accounts/$A/transactions" -d '{"type":"debit","amount":1000,"reference_id":"pay-3"}'
check 400 "invalid type" -X POST "$BASE/accounts/$A/transactions" -d '{"type":"refund","amount":1,"reference_id":"x"}'
check 400 "invalid amount" -X POST "$BASE/accounts/$A/transactions" -d '{"type":"credit","amount":-5,"reference_id":"x"}'
check 404 "unknown account" -X POST "$BASE/accounts/00000000-0000-0000-0000-000000000000/transactions" -d '{"type":"credit","amount":1,"reference_id":"x"}'

check 200 "balance of A = 100.00 (0 +100 +50 -30 -20)" "$BASE/accounts/$A/balance"
[[ "$(json "$BODY" "['balance']")" == "100.00" ]] && echo "      balance is 100.00" || { FAIL=$((FAIL + 1)); echo "FAIL  balance is not 100.00"; }

check 201 "transfer 30 A -> B" -X POST "$BASE/transfers" -d "{\"from_account_id\":\"$A\",\"to_account_id\":\"$B\",\"amount\":30,\"reference_id\":\"tr-1\"}"
check 422 "transfer 500 A -> B -> insufficient" -X POST "$BASE/transfers" -d "{\"from_account_id\":\"$A\",\"to_account_id\":\"$B\",\"amount\":500,\"reference_id\":\"tr-2\"}"
check 400 "transfer to self" -X POST "$BASE/transfers" -d "{\"from_account_id\":\"$A\",\"to_account_id\":\"$A\",\"amount\":1,\"reference_id\":\"tr-3\"}"

check 200 "history of A, debits only, page 1 of size 2" "$BASE/accounts/$A/transactions?type=debit&page=1&limit=2"
check 400 "invalid pagination" "$BASE/accounts/$A/transactions?limit=500"
check 405 "DELETE a transaction is not possible" -X DELETE "$BASE/accounts/$A/transactions"
check 200 "OpenAPI spec" "$BASE/v3/api-docs"
check 200 "Swagger UI" -L "$BASE/swagger-ui/index.html"

echo
echo "$PASS passed, $FAIL failed"
[[ "$FAIL" == 0 ]]
