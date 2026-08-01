.text 0x3000
main:
    ori $1, $0, 0x3010
    ori $2, $0, 0x417c
    jr $2
    nop

course_halt:
    beq $0, $0, course_halt
    nop

.ktext 0x4180
handler_entry:
    jr $1
    nop
