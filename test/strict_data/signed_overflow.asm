.text
lui $1, 0x7fff
ori $1, $1, 0xffff
lw $2, 1($1)

halt:
beq $0, $0, halt
nop
