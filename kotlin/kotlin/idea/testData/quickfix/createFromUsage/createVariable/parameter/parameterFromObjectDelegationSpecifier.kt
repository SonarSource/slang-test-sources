// "Create parameter 'b'" "false"
// ERROR: Unresolved reference: b
// ACTION: Create property 'b'
// ACTION: Rename reference

open class A(val a: Int) {

}

object B: A(<caret>b) {

}