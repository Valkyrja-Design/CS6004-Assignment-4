import soot.*;
import soot.jimple.*;
import soot.jimple.internal.*;
import soot.shimple.internal.*;

public class Utils{
    public static boolean isLocal(Value v){
        return v instanceof JimpleLocal;
    }

    public static boolean isIntConstant(Value v){
        return v instanceof IntConstant;
    }

    public static boolean isStringConstant(Value v){
        return v instanceof StringConstant;
    }

    public static boolean isConstant(Value v){
        return v instanceof Constant;
    }

    public static boolean isAssignmentStmt(Unit u){
        return u instanceof JAssignStmt;
    }

    public static boolean isIfStmt(Unit u){
        return u instanceof JIfStmt;
    }

    public static boolean isReturnStmt(Unit u){
        return u instanceof JReturnStmt;
    }

    public static boolean isInvokeStmt(Unit u){
        return u instanceof JInvokeStmt;
    }

    public static boolean isMulExpr(Value v){
        return v instanceof JMulExpr;
    }

    public static boolean isDivExpr(Value v){
        return v instanceof JDivExpr;
    }

    public static boolean isAddExpr(Value v){
        return v instanceof JAddExpr;
    }

    public static boolean isSubExpr(Value v){
        return v instanceof JSubExpr;
    }

    public static boolean isPhiExpr(Value v){
        return v instanceof SPhiExpr;
    }

    public static boolean isAndExpr(Value v){
        return v instanceof JAndExpr;
    }

    public static boolean isOrExpr(Value v){
        return v instanceof JOrExpr;
    }

    public static boolean isEqExpr(Value v){
        return v instanceof JEqExpr;
    }

    public static boolean isNeExpr(Value v){
        return v instanceof JNeExpr;
    }

    public static boolean isGtExpr(Value v){
        return v instanceof JGtExpr;
    }

    public static boolean isGeExpr(Value v){
        return v instanceof JGeExpr;
    }

    public static boolean isLtExpr(Value v){
        return v instanceof JLtExpr;
    }

    public static boolean isLeExpr(Value v){
        return v instanceof JLeExpr;
    }

    public static boolean isNegExpr(Value v){
        return v instanceof JNegExpr;
    }

    public static int extractIntValue(Value v){
        IntConstant c = (IntConstant) v;
        return c.value;
    }

    // prints the given object alongside its class name
    private void printObject(Object obj){
        System.out.println("[" + obj.getClass() + "] " + obj);
    }
}