public class Test {
    public static void main(String[] args) {
        foo();
    }

    public static int foo(){
        int x0 = 0;
        int y0 = 0;
        for (int i = 0; i < 100000000; ++i){
            int x = 1;
            int y = 2;
            x = x * y;
            if (x <= 2 || x < 0 && x != 0 && x == 1
                && x >= 4 || x > 2){
                x = 3;
            } else {
                x = 4;
            }
    
            boolean b = false;
            if (x != 15){
                b = 2 > x;
            } else {
                y = -x / y;
            }
    
            x0 = x;
            y0 = y;
        }

        return x0 * y0;
    }
}
