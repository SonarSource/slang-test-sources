var global = "";

function foo(x) {
    global += x;
    return x;
}

function box() {
    var i = 0;
    do {
        ++i;
        global += ";";
    } while (foo(i) < 10);

    if (global != ";1;2;3;4;5;6;7;8;9;10") return "fail: " + global;

    return "OK"
}