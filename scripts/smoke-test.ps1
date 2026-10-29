# End-to-end smoke test against a running `docker compose up` stack.
# Uses the API exactly like the Retech Bank app: sign up, log in with a JWT,
# transfer, and read verification codes from the emails delivered to Mailpit.
#   ALLOW  -> COMPLETED, receiver credited, debit/credit emails
#   REVIEW -> code by email (no links) -> correct code -> COMPLETED
#   REVIEW -> wrong code -> FLAGGED, refunded, sender BLOCKED
#   REVIEW -> "I didn't make this transfer" -> FLAGGED, refunded, sender BLOCKED
#   Security: no token, other users' accounts/transfers, internal endpoints
#   IBAN: generated per account, lookup, typos, other banks, own IBAN
# Usage:  powershell -ExecutionPolicy Bypass -File scripts\smoke-test.ps1

$ErrorActionPreference = 'Stop'
# Windows PowerShell reuses keep-alive connections the Netty gateway may have closed
$PSDefaultParameterValues['Invoke-RestMethod:DisableKeepAlive'] = $true
$gateway = 'http://localhost:8080/api/v1'
$mailpit = 'http://localhost:8025/api/v1'
$password = 'correct horse battery'

function New-User([string]$name, [decimal]$deposit) {
    $email = "$($name.ToLower())-$(Get-Random -Maximum 1000000000)@example.com"
    $body = @{ fullName = "$name Tester"; email = $email; phone = '5550000000'; password = $password;
               initialDeposit = $deposit } | ConvertTo-Json
    $r = Invoke-RestMethod -Method Post -Uri "$gateway/auth/register" -ContentType 'application/json' -Body $body
    $headers = @{ Authorization = "Bearer $($r.accessToken)" }
    $account = Invoke-RestMethod -Uri "$gateway/accounts/$($r.user.accountNumber)" -Headers $headers
    [pscustomobject]@{ email = $email; accountNumber = $r.user.accountNumber; iban = $account.iban; headers = $headers }
}

function Api([string]$method, [string]$path, $user, $body = $null) {
    $request = @{ Method = $method; Uri = "$gateway$path"; Headers = $user.headers }
    if ($body) { $request.ContentType = 'application/json'; $request.Body = ($body | ConvertTo-Json) }
    Invoke-RestMethod @request
}

function Status-Of([scriptblock]$call) {
    try { & $call | Out-Null; 200 } catch { $_.Exception.Response.StatusCode.value__ }
}

function Get-Balance($user) { [decimal](Api GET "/accounts/$($user.accountNumber)/balance" $user) }

# Sends to the recipient's IBAN, exactly like the app
function Send-Transfer($from, $to, [decimal]$amount) {
    Api POST '/transactions/transfer' $from @{ senderAccountNumber = $from.accountNumber;
        receiverIban = $to.iban; amount = $amount; description = 'smoke test' }
}

function Wait-Status($user, [string]$id, [string[]]$expected) {
    for ($i = 0; $i -lt 30; $i++) {
        $tx = Api GET "/transactions/$id" $user
        if ($expected -contains $tx.status) { return $tx }
        Start-Sleep -Milliseconds 500
    }
    throw "Transaction $id stuck in $($tx.status), expected $($expected -join '/')"
}

# Waits for an email to this address whose subject contains $subject, returns the message
function Wait-Email([string]$to, [string]$subject) {
    for ($i = 0; $i -lt 30; $i++) {
        $query = [uri]::EscapeDataString("to:`"$to`" subject:`"$subject`"")
        $found = Invoke-RestMethod "$mailpit/search?query=$query"
        if ($found.messages.Count -gt 0) {
            return Invoke-RestMethod "$mailpit/message/$($found.messages[0].ID)"
        }
        Start-Sleep -Milliseconds 500
    }
    throw "No email '$subject' arrived for $to"
}

function Get-Otp([string]$to) {
    $text = (Wait-Email $to 'verification code').Text
    if ($text -notmatch 'verification code is (\d{6})') { throw "No code in email: $text" }
    $Matches[1]
}

function Check([bool]$condition, [string]$message) {
    if (-not $condition) { throw "FAILED: $message" }
    Write-Host "  ok  $message" -ForegroundColor Green
}

Write-Host "`nSign up and log in"
$alice = New-User 'Alice' 10000
$bob = New-User 'Bob' 100
Check ($alice.accountNumber -match '^\d{12}$') "sign-up opened account $($alice.accountNumber)"
$login = Invoke-RestMethod -Method Post "$gateway/auth/login" -ContentType 'application/json' `
    -Body (@{ email = $alice.email.ToUpper(); password = $password } | ConvertTo-Json)
Check ($login.user.accountNumber -eq $alice.accountNumber) 'log-in returns the same account (email is case-insensitive)'
Check ((Status-Of { Invoke-RestMethod -Method Post "$gateway/auth/login" -ContentType 'application/json' `
    -Body (@{ email = $alice.email; password = 'wrong password' } | ConvertTo-Json) }) -eq 401) 'wrong password is rejected'

Write-Host "`nIBAN"
Check ($alice.iban -match '^TR\d{24}$' -and $alice.iban.EndsWith($alice.accountNumber)) "accounts get a TR IBAN ($($alice.iban))"
$spaced = ($bob.iban -replace '(.{4})(?!$)', '$1 ').ToLower()
Check ((Api GET "/accounts/iban/$([uri]::EscapeDataString($spaced))" $alice).displayName -eq 'Bob T.') 'IBAN lookup (spaces, lower case) shows only "Bob T."'
$typo = $bob.iban.Substring(0, 25) + $(if ($bob.iban[25] -eq '9') { '0' } else { '9' })
Check ((Status-Of { Api GET "/accounts/iban/$typo" $alice }) -eq 400) 'IBAN with a typo -> 400'
Check ((Status-Of { Api GET '/accounts/iban/TR330006100519786457841326' $alice }) -eq 422) "another bank's IBAN -> 422"
Check ((Status-Of { Api POST '/transactions/transfer' $alice @{ senderAccountNumber = $alice.accountNumber; receiverIban = $typo; amount = 1 } }) -eq 422) 'transfer to a mistyped IBAN is refused'
Check ((Status-Of { Api POST '/transactions/transfer' $alice @{ senderAccountNumber = $alice.accountNumber; receiverIban = $alice.iban; amount = 1 } }) -eq 422) 'transfer to your own IBAN is refused'

Write-Host "`nSecurity"
Check ((Status-Of { Invoke-RestMethod "$gateway/accounts/$($alice.accountNumber)/balance" }) -eq 401) 'no token -> 401'
Check ((Status-Of { Invoke-RestMethod "$gateway/accounts/$($alice.accountNumber)/balance" -Headers @{ Authorization = 'Bearer forged.token.value' } }) -eq 401) 'forged token -> 401'
Check ((Status-Of { Api GET "/accounts/$($alice.accountNumber)/balance" $bob }) -eq 404) "another user's balance is hidden"
Check ((Status-Of { Api GET "/transactions/account/$($alice.accountNumber)" $bob }) -eq 403) "another user's history is refused"
Check ((Status-Of { Api POST '/transactions/transfer' $bob @{ senderAccountNumber = $alice.accountNumber; receiverAccountNumber = $bob.accountNumber; amount = 1; description = 'steal' } }) -eq 403) "sending from someone else's account -> 403"
Check ((Status-Of { Api PUT "/accounts/$($bob.accountNumber)/credit?amount=1000000" $bob }) -eq 404) 'internal credit endpoint is not exposed'
Check ((Status-Of { Invoke-RestMethod "$gateway/auth/me" -Headers @{ 'X-User-Id' = 'spoofed' } }) -eq 401) 'spoofed X-User-Id without a token -> 401'
Check ((Get-Balance $alice) -eq 10000) 'refused requests moved no money'

Write-Host "`nALLOW path"
$tx = Send-Transfer $alice $bob 500
$tx = Wait-Status $alice $tx.id @('COMPLETED')
Check ($tx.receiverIban -eq $bob.iban) 'transfer records the IBAN it was sent to'
Start-Sleep -Seconds 2
Check ((Get-Balance $alice) -eq 9500) 'sender debited once'
Check ((Get-Balance $bob) -eq 600) 'receiver credited once'
Check ((Wait-Email $alice.email 'Debit alert').Text -match '500,00') 'sender got a debit email (Turkish lira format)'
Check ((Wait-Email $bob.email 'Credit alert').Text -match '500,00') 'receiver got a credit email'
Check ((Status-Of { Api GET "/transactions/$($tx.id)" $bob }) -eq 404) "another user can't see the transfer"

Write-Host "`nREVIEW path, correct code from the email"
$carol = New-User 'Carol' 10000
$dan = New-User 'Dan' 100
$tx = Send-Transfer $carol $dan 9500           # > 90% of the balance
$tx = Wait-Status $carol $tx.id @('PENDING_VERIFICATION')
Check ($tx.verificationSecondsRemaining -gt 240) "countdown provided ($($tx.verificationSecondsRemaining)s left)"
$email = Wait-Email $carol.email 'verification code'
Check (-not ($email.HTML -match 'href=') -and -not ($email.Text -match 'https?://')) 'verification email contains no links'
$otp = Get-Otp $carol.email
Check ($otp -eq (docker exec redis redis-cli GET "verification:otp:$($tx.id)").Trim()) "emailed code matches the stored code ($otp)"
Check (-not ((docker logs notification-service 2>&1 | Out-String).Contains($otp))) 'code never appears in the logs'
Check ((Status-Of { Api POST "/transactions/$($tx.id)/verify?otp=$otp" $dan }) -eq 404) "another user can't use the code"
$tx = Api POST "/transactions/$($tx.id)/verify?otp=$otp" $carol
Check ($tx.status -eq 'COMPLETED') 'correct code completes the transfer'
$again = Api POST "/transactions/$($tx.id)/verify?otp=000000" $carol
Check ($again.status -eq 'COMPLETED') 'verifying again does not refund a completed transfer'
Start-Sleep -Seconds 2
Check ((Get-Balance $carol) -eq 500) 'sender balance after confirmed transfer'
Check ((Get-Balance $dan) -eq 9600) 'receiver credited after confirmed transfer'

Write-Host "`nREVIEW path, wrong code"
$erin = New-User 'Erin' 1000
$tx = Send-Transfer $erin $dan 950
$tx = Wait-Status $erin $tx.id @('PENDING_VERIFICATION')
$tx = Api POST "/transactions/$($tx.id)/verify?otp=000000" $erin
Check ($tx.status -eq 'FLAGGED') 'wrong code cancels the transfer'
Start-Sleep -Seconds 2
Check ((Get-Balance $erin) -eq 1000) 'sender refunded in full'
Check ((Api GET "/accounts/$($erin.accountNumber)" $erin).status -eq 'BLOCKED') 'sender account blocked'
Check ((Wait-Email $erin.email 'Refund processed').Text -match '950,00') 'refund email sent'

Write-Host "`nREVIEW path, customer reports the transfer"
$frank = New-User 'Frank' 1000
$tx = Send-Transfer $frank $dan 950
$tx = Wait-Status $frank $tx.id @('PENDING_VERIFICATION')
$tx = Api POST "/transactions/$($tx.id)/report-fraud" $frank
Check ($tx.status -eq 'FLAGGED') '"I didn''t make this transfer" cancels it'
Start-Sleep -Seconds 2
Check ((Get-Balance $frank) -eq 1000) 'sender refunded in full'
Check ($null -ne (Wait-Email $frank.email 'account has been blocked')) 'account-blocked email sent'

Write-Host "`nRejected transfer"
Check ((Status-Of { Send-Transfer $alice $bob 999999 }) -eq 422) 'insufficient balance rejected with 422'

Write-Host "`nRust risk engine"
$metrics = Invoke-RestMethod 'http://localhost:8086/metrics'
Check ($metrics.evaluations -ge 4) "Rust engine made the decisions ($($metrics.evaluations) evaluations)"

Write-Host "`nAll smoke tests passed." -ForegroundColor Green
