.text 0x3000
main:
    j after_halt
    nop

course_halt:
    beq $0, $0, course_halt
    nop

after_halt:
    j course_halt
    nop
