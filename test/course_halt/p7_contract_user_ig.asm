.text 0x3000
main:
    sb $0, 0x7f20($0)

course_halt:
    beq $0, $0, course_halt
    nop
