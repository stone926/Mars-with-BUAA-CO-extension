.text 0x3000
main:
    ori $2, $gp, 0
    ori $3, $sp, 0
    lui $1, 0x1122
    ori $1, $1, 0x3344
    sw $1, 0($0)
    swl $1, 1($0)
    swr $1, 2($0)
    lw $4, 0x5000($0)
    sw $4, 0x5000($0)
    ori $6, $0, 1

course_halt:
    beq $0, $0, course_halt
    nop

.ktext 0x4180
handler:
    mfc0 $26, $14
    addiu $26, $26, 4
    mtc0 $26, $14
    addiu $5, $5, 1
    eret
    nop
