.text 0x3000
main:
    lui $1, 0
    ori $1, $1, 0x4000
    jr $1
    nop

course_halt:
    beq $0, $0, course_halt
    nop

.ktext 0x4000
kernel_entry:
    ori $2, $0, 1
    j course_halt
    nop
