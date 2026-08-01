@echo off
setlocal

if "%~1"=="" (
  set "MARS_JAR=%~dp0mars.jar"
) else (
  set "MARS_JAR=%~f1"
)

pushd "%~dp0"

call :run status "" || exit /b 1
call :run bd_not_taken "db" || exit /b 1
call :run fetch_unaligned "db" || exit /b 1
call :run jump_far "db" || exit /b 1
call :run external_interrupt_ip "db p7irq=0x3008" || exit /b 1
call :run timer_write_count "" || exit /b 1
call :run data_address_map "" || exit /b 1
call :run eret_delay_slot "db" || exit /b 1
call :run cp0_mask "" || exit /b 1
call :run_no_efc status_legacy "p7\status.asm" || exit /b 1
call :zero_gpr_ok || exit /b 1
call :run_strict_ok || exit /b 1
call :run_strict_fail invalid_text || exit /b 1
call :run_strict_fail invalid_kdata || exit /b 1
call :run_strict_fail signed_overflow || exit /b 1
call :run_strict_signed_recovery || exit /b 1
call :halt_ok "" 0x20 || exit /b 1
call :halt_ok "" 0x21 || exit /b 1
call :halt_ok "db" 0x20 || exit /b 1
call :halt_ok "db" 0x21 || exit /b 1
call :halt_fail cliff 0x3008 "" || exit /b 1
call :halt_fail cliff 0x3008 "db" || exit /b 1
call :halt_fail other_loop 0x3008 "db" || exit /b 1
call :halt_fail delay_slot_only 0x3008 "" || exit /b 1
call :halt_fail delay_slot_only 0x3008 "db" || exit /b 1
call :halt_fail standard 0x3000 "" || exit /b 1
call :halt_fetch_fail kernel_text_escape 0x3010 "" CompactDataAtZero || exit /b 1
call :halt_fetch_fail kernel_text_escape 0x3010 "db" CompactDataAtZero || exit /b 1
call :halt_fetch_fail post_halt_escape 0x3008 "" CompactLargeText || exit /b 1
call :halt_fetch_fail post_halt_escape 0x3008 "db" CompactLargeText || exit /b 1
call :p7_halt_fetch_fail p7_kernel_escape "" || exit /b 1
call :p7_halt_fetch_fail p7_kernel_escape "db" || exit /b 1
call :p7_padding_ok "" || exit /b 1
call :p7_padding_ok "db" || exit /b 1
call :p7_handler_ok "" || exit /b 1
call :p7_handler_ok "db" || exit /b 1
call :p7_fetch_exception_ok p7_fetch_outside "" || exit /b 1
call :p7_fetch_exception_ok p7_fetch_outside "db" || exit /b 1
call :p7_fetch_exception_ok p7_fetch_unaligned "" || exit /b 1
call :p7_fetch_exception_ok p7_fetch_unaligned "db" || exit /b 1
call :text_limit_ok FixedCompactLargeText coStrictData || exit /b 1
call :text_limit_ok FixedCompactLargeText efc || exit /b 1
call :text_limit_ok CompactLargeText coStrictData || exit /b 1
call :text_limit_ok CompactLargeText efc || exit /b 1
call :text_limit_legacy_fail || exit /b 1
call :kernel_text_limit_ok coStrictData || exit /b 1
call :kernel_text_limit_ok efc || exit /b 1
call :kernel_text_limit_legacy_ok || exit /b 1
call :p7_contract_ok "" || exit /b 1
call :p7_contract_ok "db" || exit /b 1
call :p7_contract_fail p7_contract_user_ig "" || exit /b 1
call :p7_contract_fail p7_contract_handler_ig_encoding "" || exit /b 1
call :p7_contract_fail p7_contract_handler_exception "" || exit /b 1
call :p7_contract_fail p7_contract_new_hwint "p7irq=0x4180" || exit /b 1
call :p7_contract_fail_at p7_contract_nested_interrupt "p7irq=0x3008" 0x3010 || exit /b 1
call :p7_contract_fail_at p7_contract_valid_irq "p7irq=0x3008,0x4188" 0x3010 || exit /b 1
call :legacy_cliff || exit /b 1
call :legacy_kernel_text || exit /b 1

del /q testTemp.txt testActual.txt testExpected.txt >nul 2>nul
popd
exit /b 0

:run
set "NAME=%~1"
set "EXTRA=%~2"
java -jar "%MARS_JAR%" nc mc CompactLargeText coL1 efc %EXTRA% "p7\%NAME%.asm" > testTemp.txt
findstr /v "^$" testTemp.txt > testActual.txt
findstr /v "^$" "p7\%NAME%.out" > testExpected.txt
fc testActual.txt testExpected.txt
exit /b %errorlevel%

:run_no_efc
set "NAME=%~1"
set "ASM=%~2"
java -jar "%MARS_JAR%" nc mc CompactLargeText coL1 "%ASM%" > testTemp.txt
findstr /v "^$" testTemp.txt > testActual.txt
findstr /v "^$" "p7\%NAME%.out" > testExpected.txt
fc testActual.txt testExpected.txt
exit /b %errorlevel%

:zero_gpr_ok
java -jar "%MARS_JAR%" nc mc CompactLargeText coZeroGpr coHalt=0x3008 coL1 ae1 se1 0x20 "co_zero_gpr.asm" > testTemp.txt
if errorlevel 1 exit /b 1
findstr /l /c:"@00003000: $ 2 <= 00000000" testTemp.txt >nul || exit /b 1
findstr /l /c:"@00003004: $ 3 <= 00000000" testTemp.txt >nul || exit /b 1
findstr /l /c:"Program reached course halt loop at 0x00003008." testTemp.txt >nul || exit /b 1
exit /b 0

:run_strict_ok
java -jar "%MARS_JAR%" nc mc CompactLargeText coStrictData coL1 ae1 se1 0x80 "strict_data\valid_boundary.asm" > testTemp.txt
if errorlevel 1 exit /b 1
findstr /l /c:"@00003004: *00002ffc <= 5a000000" testTemp.txt >nul || exit /b 1
findstr /l /c:"@00003008: $ 2 <= 0000005a" testTemp.txt >nul || exit /b 1
exit /b 0

:run_strict_fail
java -jar "%MARS_JAR%" nc mc CompactLargeText coStrictData ae1 se1 "strict_data\%~1.asm" > testTemp.txt
if not "%errorlevel%"=="1" exit /b 1
findstr /l /c:"Runtime exception" testTemp.txt >nul || exit /b 1
findstr /l /c:"Processing terminated due to errors." testTemp.txt >nul || exit /b 1
exit /b 0

:run_strict_signed_recovery
java -jar "%MARS_JAR%" nc mc CompactLargeText coStrictData coL1 ae1 se1 0x80 "strict_data\overflow_wrap.asm" > testTemp.txt
if errorlevel 1 exit /b 1
findstr /l /c:"@00003004: $ 2 <= 00000000" testTemp.txt >nul || exit /b 1
exit /b 0

:halt_ok
java -jar "%MARS_JAR%" nc mc CompactLargeText coHalt=0x3004 ae1 se1 %~2 %~1 "course_halt\standard.asm" > testTemp.txt
if errorlevel 1 exit /b 1
findstr /l /c:"Program reached course halt loop at 0x00003004." testTemp.txt >nul || exit /b 1
exit /b 0

:halt_fail
java -jar "%MARS_JAR%" nc mc CompactLargeText coHalt=%~2 ae1 se1 0x20 %~3 "course_halt\%~1.asm" > testTemp.txt
if not "%errorlevel%"=="1" exit /b 1
findstr /l /c:"course halt" /c:"Course instruction address out of range" testTemp.txt >nul || exit /b 1
findstr /l /c:"Program reached course halt loop" testTemp.txt >nul
if not errorlevel 1 exit /b 1
exit /b 0

:halt_fetch_fail
java -jar "%MARS_JAR%" nc mc %~4 coHalt=%~2 ae1 se1 0x20 %~3 "course_halt\%~1.asm" > testTemp.txt
if not "%errorlevel%"=="1" exit /b 1
findstr /l /c:"Course instruction address out of range" testTemp.txt >nul || exit /b 1
findstr /l /c:"Program reached course halt loop" testTemp.txt >nul
if not errorlevel 1 exit /b 1
exit /b 0

:p7_halt_fetch_fail
java -jar "%MARS_JAR%" nc mc CompactLargeText efc coHalt=0x3010 ae1 se1 0x40 %~2 "course_halt\%~1.asm" > testTemp.txt
if not "%errorlevel%"=="1" exit /b 1
findstr /l /c:"Course instruction address out of range" testTemp.txt >nul || exit /b 1
findstr /l /c:"Program reached course halt loop" testTemp.txt >nul
if not errorlevel 1 exit /b 1
exit /b 0

:p7_padding_ok
java -jar "%MARS_JAR%" nc mc CompactLargeText efc coHalt=0x3010 coL2 ae1 se1 0x40 %~1 "course_halt\p7_padding_to_handler.asm" > testTemp.txt
if errorlevel 1 exit /b 1
findstr /l /c:"@PC0000417c -> nop (00000000)" testTemp.txt >nul || exit /b 1
findstr /l /c:"Program reached course halt loop at 0x00003010." testTemp.txt >nul || exit /b 1
findstr /l /c:"Course instruction address out of range" testTemp.txt >nul
if not errorlevel 1 exit /b 1
exit /b 0

:p7_handler_ok
java -jar "%MARS_JAR%" nc mc CompactLargeText coZeroGpr coStrictData efc coHalt=0x3028 coL1 ae1 se1 0x80 %~1 "course_halt\p7_double_exception.asm" > testTemp.txt
if errorlevel 1 exit /b 1
findstr /l /c:"@0000418c: $ 5 <= 00000002" testTemp.txt >nul || exit /b 1
findstr /l /c:"Program reached course halt loop at 0x00003028." testTemp.txt >nul || exit /b 1
findstr /l /c:"Course instruction address out of range" testTemp.txt >nul
if not errorlevel 1 exit /b 1
exit /b 0

:p7_fetch_exception_ok
java -jar "%MARS_JAR%" nc mc CompactLargeText efc coHalt=0x3010 ae1 se1 0x40 %~2 "course_halt\%~1.asm" > testTemp.txt
if errorlevel 1 exit /b 1
findstr /l /c:"Program reached course halt loop at 0x00003010." testTemp.txt >nul || exit /b 1
findstr /l /c:"Course instruction address out of range" testTemp.txt >nul
if not errorlevel 1 exit /b 1
exit /b 0

:text_limit_ok
java -jar "%MARS_JAR%" a nc mc %~1 %~2 ae1 dump 0x00006ff8-0x00007000 HexText testActual.txt "course_halt\text_limit_boundary.asm" > testTemp.txt
if errorlevel 1 exit /b 1
fc testActual.txt "course_halt\text_limit_boundary.out" >nul || exit /b 1
exit /b 0

:text_limit_legacy_fail
java -jar "%MARS_JAR%" a nc mc FixedCompactLargeText ae1 "course_halt\text_limit_boundary.asm" > testTemp.txt
if not "%errorlevel%"=="1" exit /b 1
findstr /l /c:"Invalid address for text segment: 28668" testTemp.txt >nul || exit /b 1
exit /b 0

:kernel_text_limit_ok
java -jar "%MARS_JAR%" a nc mc CompactLargeText %~1 ae1 dump 0x00006ff8-0x00007000 HexText testActual.txt "course_halt\kernel_text_limit_boundary.asm" > testTemp.txt
if errorlevel 1 exit /b 1
fc testActual.txt "course_halt\kernel_text_limit_boundary.out" >nul || exit /b 1
exit /b 0

:kernel_text_limit_legacy_ok
java -jar "%MARS_JAR%" a nc mc CompactLargeText ae1 dump 0x00006ff8-0x00007000 HexText testActual.txt "course_halt\kernel_text_limit_boundary.asm" > testTemp.txt
if errorlevel 1 exit /b 1
fc testActual.txt "course_halt\kernel_text_limit_boundary.out" >nul || exit /b 1
exit /b 0

:p7_contract_ok
java -jar "%MARS_JAR%" nc mc CompactLargeText coZeroGpr coStrictData efc coHalt=0x3010 coL2 p7irq=0x3008 ae1 se1 0x80 %~1 "course_halt\p7_contract_valid_irq.asm" > testTemp.txt
if errorlevel 1 exit /b 1
findstr /l /c:"(a0007f20)" testTemp.txt >nul || exit /b 1
findstr /l /c:"Program reached course halt loop at 0x00003010." testTemp.txt >nul || exit /b 1
findstr /l /c:"Course P7 test contract violation" testTemp.txt >nul
if not errorlevel 1 exit /b 1
exit /b 0

:p7_contract_fail
java -jar "%MARS_JAR%" nc mc CompactLargeText coZeroGpr coStrictData efc coHalt=0x3004 ae1 se1 0x80 %~2 "course_halt\%~1.asm" > testTemp.txt
if not "%errorlevel%"=="1" exit /b 1
findstr /l /c:"Course P7 test contract violation" testTemp.txt >nul || exit /b 1
findstr /l /c:"Program reached course halt loop" testTemp.txt >nul
if not errorlevel 1 exit /b 1
exit /b 0

:p7_contract_fail_at
java -jar "%MARS_JAR%" nc mc CompactLargeText coZeroGpr coStrictData efc coHalt=%~3 ae1 se1 0x80 %~2 "course_halt\%~1.asm" > testTemp.txt
if not "%errorlevel%"=="1" exit /b 1
findstr /l /c:"Course P7 test contract violation" testTemp.txt >nul || exit /b 1
findstr /l /c:"Program reached course halt loop" testTemp.txt >nul
if not errorlevel 1 exit /b 1
exit /b 0

:legacy_cliff
java -jar "%MARS_JAR%" nc mc CompactLargeText ae1 se1 0x20 "course_halt\cliff.asm" > testTemp.txt
if errorlevel 1 exit /b 1
findstr /l /c:"course halt" testTemp.txt >nul && exit /b 1
exit /b 0


:legacy_kernel_text
java -jar "%MARS_JAR%" nc mc CompactDataAtZero ae1 se1 0x20 "course_halt\kernel_text_escape.asm" > testTemp.txt
if errorlevel 1 exit /b 1
findstr /l /c:"Course instruction address out of range" testTemp.txt >nul && exit /b 1
exit /b 0
