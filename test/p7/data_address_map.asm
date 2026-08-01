.text
# CompactLargeText maps 0x5000 as .kdata, but the course Bridge does not.
lw $1,0x5000($0)
sw $1,0x5000($0)

# Boundary DM, Timer0 and interrupt-generator accesses remain legal.
ori $5,$0,0x5a
sb $5,0x2fff($0)
lbu $6,0x2fff($0)
sw $0,0x7f00($0)
lw $7,0x7f00($0)
sb $0,0x7f20($0)
nop

.ktext 0x4180
mfc0 $2,$13
mfc0 $4,$14
addi $4,$4,4
mtc0 $4,$14
eret
