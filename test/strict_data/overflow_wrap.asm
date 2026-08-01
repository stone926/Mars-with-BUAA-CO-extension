.text
addiu $1, $0, -1
lbu $2, 1($1)

halt:
beq $0, $0, halt
nop
