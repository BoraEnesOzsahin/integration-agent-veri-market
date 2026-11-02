pragma circom 2.1.6;

include "../node_modules/circomlib/circuits/poseidon.circom";
include "../node_modules/circomlib/circuits/comparators.circom";
include "../node_modules/circomlib/circuits/bitify.circom";

// Taahhüt edilen değerin bir eşiğe göre koşulu sağladığını, değeri açmadan kanıtlar.
// value ve limit, negatif sayılar da sığsın diye 2^63 eklenmiş olarak 64 bit tutulur.
// op: 0 ">", 1 ">=", 2 "<", 3 "<="
template Predicate() {
    signal input value;
    signal input salt;
    signal input commitment;
    signal input limit;
    signal input op;
    signal input nonce;

    component valueBits = Num2Bits(64);
    valueBits.in <== value;
    component limitBits = Num2Bits(64);
    limitBits.in <== limit;
    component opBits = Num2Bits(2);
    opBits.in <== op;

    component hash = Poseidon(2);
    hash.inputs[0] <== value;
    hash.inputs[1] <== salt;
    hash.out === commitment;

    component less = LessThan(64);
    less.in[0] <== value;
    less.in[1] <== limit;
    component equal = IsEqual();
    equal.in[0] <== value;
    equal.in[1] <== limit;

    signal lessOrEqual <== less.out + equal.out;
    signal greater <== 1 - lessOrEqual;
    signal greaterOrEqual <== 1 - less.out;

    signal aboveChoice <== opBits.out[0] * (greaterOrEqual - greater) + greater;
    signal belowChoice <== opBits.out[0] * (lessOrEqual - less.out) + less.out;
    signal satisfied <== opBits.out[1] * (belowChoice - aboveChoice) + aboveChoice;
    satisfied === 1;

    // Kanıtı tüketicinin nonce'una bağlar: eski kanıt başka sorguda kullanılamaz
    signal nonceBinding <== nonce * nonce;
}

component main {public [commitment, limit, op, nonce]} = Predicate();
