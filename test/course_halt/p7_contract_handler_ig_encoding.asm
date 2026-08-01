.text 0x3000
main:
    lw $1, 0x5000($0)

course_halt:
    beq $0, $0, course_halt
    nop

.ktext 0x4180
handler:
    ori $26, $0, 0x7f20
    sb $0, 0($26)
    eret
    nop
