.text
ori $1, $0, 0x5a
sb $1, 0x2fff($0)
lbu $2, 0x2fff($0)

halt:
beq $0, $0, halt
nop
