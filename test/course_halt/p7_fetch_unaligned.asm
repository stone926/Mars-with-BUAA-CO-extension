.text 0x3000
main:
    ori $1, $0, 0x3002
    jr $1
    nop
    nop

course_halt:
    beq $0, $0, course_halt
    nop

.ktext 0x4180
handler:
    ori $26, $0, 0x3010
    mtc0 $26, $14
    eret
    nop
