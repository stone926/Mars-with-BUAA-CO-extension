.text
sw $0, 0x5000($0)

halt:
beq $0, $0, halt
nop
