.text 0x3000
main:
    lw $1, 0x5000($0)

course_halt:
    beq $0, $0, course_halt
    nop

.ktext 0x4180
handler:
    lw $2, 0x5000($0)
    eret
    nop
