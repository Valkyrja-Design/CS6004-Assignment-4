public class Test2 {
    public static void main(String[] args) {
        foo();
    }

    public static void foo(){
        int a = 10;
        int b = 20;

        b = b + 20;
        if (b == 20){
            a = 30;
        } 

        int c = a;

        System.out.println(a);
        System.out.println(b);
        System.out.println(c);
    }
}
