import java.io.FileWriter;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import soot.Body;
import soot.BodyTransformer;
import soot.BooleanType;
import soot.Local;
import soot.Unit;
import soot.UnitPatchingChain;
import soot.Value;
import soot.baf.GotoInst;
import soot.jimple.Constant;
import soot.jimple.IntConstant;
import soot.jimple.InvokeExpr;
import soot.jimple.Jimple;
import soot.jimple.StringConstant;
import soot.jimple.internal.JAddExpr;
import soot.jimple.internal.JAndExpr;
import soot.jimple.internal.JAssignStmt;
import soot.jimple.internal.JDivExpr;
import soot.jimple.internal.JEqExpr;
import soot.jimple.internal.JGeExpr;
import soot.jimple.internal.JGotoStmt;
import soot.jimple.internal.JGtExpr;
import soot.jimple.internal.JIfStmt;
import soot.jimple.internal.JInvokeStmt;
import soot.jimple.internal.JLeExpr;
import soot.jimple.internal.JLtExpr;
import soot.jimple.internal.JMulExpr;
import soot.jimple.internal.JNeExpr;
import soot.jimple.internal.JNegExpr;
import soot.jimple.internal.JOrExpr;
import soot.jimple.internal.JReturnStmt;
import soot.jimple.internal.JSubExpr;
import soot.jimple.internal.JimpleLocal;
import soot.shimple.ShimpleBody;
import soot.shimple.internal.SPhiExpr;
import soot.shimple.toolkits.scalar.ShimpleLocalDefs;
import soot.shimple.toolkits.scalar.ShimpleLocalUses;
import soot.toolkits.scalar.UnitValueBoxPair;
import soot.util.Chain;

public class AnalysisTransformer extends BodyTransformer {
    @Override
    protected void internalTransform(Body body, String phaseName, Map<String, String> options){
        if (body.getMethod().isConstructor())
            return;
        // write the unchanged shimple body to a file
        String fileName = body.getMethod().getName() + ".shimple";
        try {
            FileWriter fWriter = new FileWriter(fileName);
            fWriter.write(body.toString());
            fWriter.close();
        } catch (IOException e) {
            System.err.println("Couldn't open file '" + fileName + "'");
            e.printStackTrace();
        }

        System.out.println("---------------------- " + body.getMethod().getName() + " ----------------------");

        new SimpleConstPropagation(body);
    }
}

/*
 * Reference: https://people.iith.ac.in/ramakrishna/fc5264/ssa-intro-construct.pdf
 * A very simple constant folder and propagator
 */
class SimpleConstPropagation{
    SimpleConstPropagation(Body body){
        this.body = (ShimpleBody) body;
        analyze();
    }

    private void analyze(){
        final String sep = "----------------------";

        UnitPatchingChain units = body.getUnits();
        // use-def chain
        ShimpleLocalDefs localDefs = new ShimpleLocalDefs(body);
        // def-use chain
        ShimpleLocalUses localUses = new ShimpleLocalUses(body);
        // all locals
        Chain<Local> locals = body.getLocals();

        // number of uses of an unit, will be useful to find dead stmts
        HashMap<Unit, Integer> useCount = new HashMap<>();
        for (Local local : locals){
            List<Unit> defs = localDefs.getDefsOf(local);
            if (defs.isEmpty())
                continue;

            useCount.put(defs.get(0), localUses.getUsesOf(local).size());
        }
        
        // mapping between old and new stmt, used when simplifying
        HashMap<Unit, Unit> replaceWith = new HashMap<>();
        System.out.println("Units:");
        for (Unit u : units){
            printObject(u);
            replaceWith.put(u, (Unit) u.clone());
        }

        // mapping between if statement and the branch to replace
        // it with
        HashMap<JIfStmt, JGotoStmt> replaceIfWith = new HashMap<>();
        HashSet<Unit> deadStmts = new HashSet<>();

        System.out.println(sep + sep + sep);

        LinkedList<Unit> q = new LinkedList<>();
        q.addAll(units);

        // process each statement
        while (!q.isEmpty()){
            Unit u = q.pollFirst();
            Unit changedU = replaceWith.get(u);
            
            System.out.println("Original:");
            printObject(u);
            System.out.println("Changed:");
            printObject(changedU);

            if (Utils.isAssignmentStmt(changedU)){
                JAssignStmt stmt = (JAssignStmt) changedU;
                Value lhs = stmt.getLeftOp();
                
                // can't handle field ref
                if (!Utils.isLocal(lhs))
                    continue;
                
                JimpleLocal local = (JimpleLocal) lhs;
                Value rhs = stmt.getRightOp();
                
                printObject(rhs);

                // x = phi(c, c, c, ...) for some c
                if (Utils.isPhiExpr(rhs) && phiExprAllConstant((SPhiExpr) rhs)){
                    SPhiExpr phiExpr = (SPhiExpr) rhs;
                    stmt.setRightOp(
                        IntConstant.v(
                            Utils.extractIntValue(phiExpr.getValue(0))
                        )
                    );

                    rhs = stmt.getRightOp();
                }

                if (Utils.isIntConstant(rhs)){       // x = c
                    // propagate the constant to all uses
                    for (Object useValueBox : localUses.getUsesOf(u)){
                        UnitValueBoxPair pair = (UnitValueBoxPair) useValueBox;
                        Unit use = pair.getUnit();

                        // simplify the use with the value of this unit's local
                        replaceWith.put(
                            use, 
                            simplify(
                                use,
                                replaceWith.get(use), 
                                local, (IntConstant) rhs,
                                deadStmts
                            )
                        );

                        q.add(use);
                    }
                }      
                
            } 
        }

        // remove all dead stmts
        units.removeAll(deadStmts);

        // swap the units with their changed versions
        for (Map.Entry<Unit, Unit> entry : replaceWith.entrySet()){
            if (units.contains(entry.getKey()))
                units.swapWith(entry.getKey(), entry.getValue());
        }
        
        System.out.println(sep + sep + sep);
        for (Unit u : units){
            printObject(u);
        }

        System.out.println(sep + sep + sep);
        printObject(body);

        System.out.println(sep + sep + sep);
        printObject(body.toJimpleBody());
    }

    private Unit simplify(
        Unit original,
        Unit u, 
        JimpleLocal var, 
        IntConstant value,
        HashSet<Unit> deadStmts){

        // assignment stmt
        if (Utils.isAssignmentStmt(u)){
            JAssignStmt stmt = (JAssignStmt) u;
            Value rhs = stmt.getRightOp();

            // unchanged
            if (Utils.isConstant(rhs))
                return u;

            // Phi expr
            if (Utils.isPhiExpr(rhs)){
                SPhiExpr phiExpr = (SPhiExpr) rhs;

                for (int i = 0; i < phiExpr.getArgCount(); ++i){
                    Value arg = phiExpr.getValue(i);
                    
                    // if this argument is equal to 'var'
                    if (isEquiv(arg, var)){
                        
                        // replace it with 'value'
                        phiExpr.setValue(i, value);
                    }                    
                }
            }

            // addition
            if (Utils.isAddExpr(rhs)){
                JAddExpr expr = (JAddExpr) rhs;
                Value left = expr.getOp1();
                Value right = expr.getOp2();

                if (isEquiv(left, var)){
                    expr.setOp1(value);
                    left = value;
                }

                if (isEquiv(right, var)){
                    expr.setOp2(value);
                    right = value;
                }

                if (Utils.isIntConstant(left)
                    && Utils.isIntConstant(right)){
                    stmt.setRightOp(
                        IntConstant.v(
                            Utils.extractIntValue(left)
                            + Utils.extractIntValue(right)
                        )
                    );
                }
            }

            // subtraction
            if (Utils.isSubExpr(rhs)){
                JSubExpr expr = (JSubExpr) rhs;
                Value left = expr.getOp1();
                Value right = expr.getOp2();

                if (isEquiv(left, var)){
                    expr.setOp1(value);
                    left = value;
                }

                if (isEquiv(right, var)){
                    expr.setOp2(value);
                    right = value;
                }

                if (Utils.isIntConstant(left)
                    && Utils.isIntConstant(right)){
                    stmt.setRightOp(
                        IntConstant.v(
                            Utils.extractIntValue(left)
                            - Utils.extractIntValue(right)
                        )
                    );
                }
            }
            
            // multiplication
            if (Utils.isMulExpr(rhs)){
                JMulExpr expr = (JMulExpr) rhs;
                Value left = expr.getOp1();
                Value right = expr.getOp2();

                if (isEquiv(left, var)){
                    expr.setOp1(value);
                    left = value;
                }

                if (isEquiv(right, var)){
                    expr.setOp2(value);
                    right = value;
                }

                if (Utils.isIntConstant(left)
                    && Utils.isIntConstant(right)){
                    stmt.setRightOp(
                        IntConstant.v(
                            Utils.extractIntValue(left)
                            * Utils.extractIntValue(right)
                        )
                    );
                }
            }

            // division
            if (Utils.isDivExpr(rhs)){
                JDivExpr expr = (JDivExpr) rhs;
                Value left = expr.getOp1();
                Value right = expr.getOp2();

                if (isEquiv(left, var)){
                    expr.setOp1(value);
                    left = value;
                }

                if (isEquiv(right, var)){
                    expr.setOp2(value);
                    right = value;
                }

                if (Utils.isIntConstant(left)
                    && Utils.isIntConstant(right)){
                    stmt.setRightOp(
                        IntConstant.v(
                            Utils.extractIntValue(left)
                            / Utils.extractIntValue(right)
                        )
                    );
                }
            }

            // negation
            if (Utils.isNegExpr(rhs)){
                JNegExpr expr = (JNegExpr) rhs;
                Value op = expr.getOp();

                if (isEquiv(op, var)){
                    stmt.setRightOp(IntConstant.v(-value.value));
                }
            }
        }

        // if stmt
        if (Utils.isIfStmt(u)){
            JIfStmt stmt = (JIfStmt) u;
            Value cond = stmt.getCondition();
            boolean fallThrough = false;
            boolean branch = false;

            // System.out.println("Simplify:");
            // printObject(stmt);
            // printObject(cond);

            // >
            if (Utils.isGtExpr(cond)){
                JGtExpr expr = (JGtExpr) cond;
                Value left = expr.getOp1();
                Value right = expr.getOp2();

                if (isEquiv(left, var)){
                    expr.setOp1(value);
                    left = value;
                }

                if (isEquiv(right, var)){
                    expr.setOp2(value);
                    right = value;
                }

                if (Utils.isIntConstant(left)
                    && Utils.isIntConstant(right)){
                    
                    // evaluate the branch condition
                    boolean condition = Utils.extractIntValue(left)
                                    > Utils.extractIntValue(right);
                    
                    // if the condition is true, we'll replace
                    // if stmt with goto to target
                    if (condition)
                        branch = true;
                    else                    // otherwise we delete the if stmt
                        fallThrough = true;
                }
            }

            // >=
            if (Utils.isGeExpr(cond)){
                JGeExpr expr = (JGeExpr) cond;
                Value left = expr.getOp1();
                Value right = expr.getOp2();

                if (isEquiv(left, var)){
                    expr.setOp1(value);
                    left = value;
                }

                if (isEquiv(right, var)){
                    expr.setOp2(value);
                    right = value;
                }

                if (Utils.isIntConstant(left)
                    && Utils.isIntConstant(right)){
                    
                    // evaluate the branch condition
                    boolean condition = Utils.extractIntValue(left)
                                    >= Utils.extractIntValue(right);
                    
                    // if the condition is true, we'll replace
                    // if stmt with goto to target
                    if (condition)
                        branch = true;
                    else                    // otherwise we delete the if stmt
                        fallThrough = true;
                }
            }

            // <
            if (Utils.isLtExpr(cond)){
                JLtExpr expr = (JLtExpr) cond;
                Value left = expr.getOp1();
                Value right = expr.getOp2();

                if (isEquiv(left, var)){
                    expr.setOp1(value);
                    left = value;
                }

                if (isEquiv(right, var)){
                    expr.setOp2(value);
                    right = value;
                }

                if (Utils.isIntConstant(left)
                    && Utils.isIntConstant(right)){
                    
                    // evaluate the branch condition
                    boolean condition = Utils.extractIntValue(left)
                                    < Utils.extractIntValue(right);
                    
                    // if the condition is true, we'll replace
                    // if stmt with goto to target
                    if (condition)
                        branch = true;
                    else                    // otherwise we delete the if stmt
                        fallThrough = true;
                }
            }

            // <=
            if (Utils.isLeExpr(cond)){
                JLeExpr expr = (JLeExpr) cond;
                Value left = expr.getOp1();
                Value right = expr.getOp2();

                if (isEquiv(left, var)){
                    expr.setOp1(value);
                    left = value;
                }

                if (isEquiv(right, var)){
                    expr.setOp2(value);
                    right = value;
                }

                if (Utils.isIntConstant(left)
                    && Utils.isIntConstant(right)){
                    
                    // evaluate the branch condition
                    boolean condition = Utils.extractIntValue(left)
                                    <= Utils.extractIntValue(right);
                    
                    // if the condition is true, we'll replace
                    // if stmt with goto to target
                    if (condition)
                        branch = true;
                    else                    // otherwise we delete the if stmt
                        fallThrough = true;
                }
            }

            // ==
            if (Utils.isEqExpr(cond)){
                JEqExpr expr = (JEqExpr) cond;
                Value left = expr.getOp1();
                Value right = expr.getOp2();

                if (isEquiv(left, var)){
                    expr.setOp1(value);
                    left = value;
                }

                if (isEquiv(right, var)){
                    expr.setOp2(value);
                    right = value;
                }

                if (Utils.isIntConstant(left)
                    && Utils.isIntConstant(right)){
                    
                    // evaluate the branch condition
                    boolean condition = Utils.extractIntValue(left)
                                    == Utils.extractIntValue(right);
                    
                    // if the condition is true, we'll replace
                    // if stmt with goto to target
                    if (condition)
                        branch = true;
                    else                    // otherwise we delete the if stmt
                        fallThrough = true;
                }
            }

            // !=
            if (Utils.isNeExpr(cond)){
                JNeExpr expr = (JNeExpr) cond;
                Value left = expr.getOp1();
                Value right = expr.getOp2();

                if (isEquiv(left, var)){
                    expr.setOp1(value);
                    left = value;
                }

                if (isEquiv(right, var)){
                    expr.setOp2(value);
                    right = value;
                }

                if (Utils.isIntConstant(left)
                    && Utils.isIntConstant(right)){
                    
                    // evaluate the branch condition
                    boolean condition = Utils.extractIntValue(left)
                                    != Utils.extractIntValue(right);
                    
                    // if the condition is true, we'll replace
                    // if stmt with goto to target
                    if (condition)
                        branch = true;
                    else                    // otherwise we delete the if stmt
                        fallThrough = true;
                }
            }

            // if branch then replace if with goto to branch target
            if (branch)
                return Jimple.v().newGotoStmt(stmt.getTargetBox());
            else                // otherwise we fall through so delete if stmt
                deadStmts.add(original);
        }

        // return stmt
        if (Utils.isReturnStmt(u)){
            JReturnStmt stmt = (JReturnStmt) u;
            Value op = stmt.getOp();

            if (isEquiv(op, var)){
                stmt.setOp(value);
            }
        }

        // invoke stmt
        if (Utils.isInvokeStmt(u)){
            JInvokeStmt stmt = (JInvokeStmt) u;
            
            if (stmt.containsInvokeExpr()){
                InvokeExpr expr = stmt.getInvokeExpr();

                // check all arguments
                for (int i = 0; i < expr.getArgCount(); ++i){
                    Value arg = expr.getArg(i);

                    if (isEquiv(arg, var)){
                        expr.setArg(i, value);
                    }
                }
            }
        }   

        return u;
    }
    
    private boolean isEquiv(Value v, JimpleLocal local){
        return Utils.isLocal(v) && local.equivTo((JimpleLocal) v);
    }

    // returns true if all the args of the given phi expr
    // are equal to the same constant
    private boolean phiExprAllConstant(SPhiExpr expr){
        if (expr.getArgs().isEmpty())
            return false;
        
        // first arg
        Value arg = expr.getValue(0);
        if (!Utils.isIntConstant(arg))
            return false;
        
        // check the rest of the args
        IntConstant c = (IntConstant) arg;
        for (int i = 1; i < expr.getArgCount(); ++i){
            arg = expr.getValue(i);

            if (!Utils.isIntConstant(arg)
                || !c.equivTo((IntConstant) arg))
                return false;
        }

        return true;
    }
    
    // prints the given object alongside its class name
    private void printObject(Object obj){
        System.out.println("[" + obj.getClass() + "] " + obj);
    }

    private ShimpleBody body;
}

class SparseConditionalConstPropagation{
    SparseConditionalConstPropagation(Body body){
        this.body = (ShimpleBody) body;
    }

    private ShimpleBody body;
}

class Utils{
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
}