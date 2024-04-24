public class Test {
    public static void main(String[] args) {
        System.out.println(foo());
    }

    public static int foo(){
        int x = 1;
        System.out.println(x);
        int y = 2;
        System.out.println(y);
        x = x * y;
        System.out.println(x);
        if (x <= 2 || x < 0 && x != 0 && x == 1
            && x >= 4 || x > 2){
            x = 3;
        } else {
            x = 4;
        }

        System.out.println(x);

        boolean b = false;
        if (x != 15){
            b = 2 > x;
            System.out.println(b);
        } else {
            y = -x / y;
            System.err.println(y);
        }

        System.out.println(x);
        System.out.println(y);

        return x * y;
    }
}
