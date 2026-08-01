.text
j escape
nop

halt:
beq $0, $0, halt
nop

escape:
ori $1, $0, 1
