class A {
    companion object {
        val prop = test.lineNumber()
        
        fun foo(): Int {
            return test.lineNumber()
        }
    }
}
