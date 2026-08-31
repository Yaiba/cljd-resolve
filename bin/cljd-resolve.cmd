@echo off
rem The resolve daemon, speaking newline-delimited JSON-RPC on stdio.
rem %~dp0 is this launcher's directory, including its trailing backslash, so
rem bb.edn is found relative to the checkout no matter what the caller's cwd is.
bb --config "%~dp0..\bb.edn" -m cljd-resolve.daemon %*
