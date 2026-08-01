.text 0x3000
main:
    ori $26, $0, 0x1001
    mtc0 $26, $12
    nop
    ori $6, $0, 1

course_halt:
    beq $0, $0, course_halt
    nop

.ktext 0x4180
handler:
    ori $26, $0, 0x1001
    mtc0 $26, $12
    nop
    nop
    eret
    nop
