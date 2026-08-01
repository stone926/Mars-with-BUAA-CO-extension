.text
lw $1, 0x3000($0)

halt:
beq $0, $0, halt
nop
