.text 0x3000
main:
    syscall

course_halt:
    beq $0, $0, course_halt
    nop

.ktext 0x4180
handler:
    mfc0 $26, $14
    addiu $26, $26, 4
    mtc0 $26, $14
    eret
    nop
