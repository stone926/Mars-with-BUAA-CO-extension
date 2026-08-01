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
    mfc0 $8, $13
    sb $0, 0x7f20($0)
    eret
    nop
