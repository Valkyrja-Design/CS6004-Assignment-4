public class Test3 {
    public static void main(String[] args) {
        foo();
    }

    public static void foo(){
        int a = 1;
        int b = 1;
        int c = 0;

        while (a < 1000000000){
            int x = b + c;

            if (c != 100){
                if (b < 10){
                    c += 2;
                    x += 2;
                } else {
                    --x;
                }
            } else {
                break;
            }

            ++c;
            ++a;
        }

        System.out.println(a);
    }
}
