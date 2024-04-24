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
import soot.Local;
import soot.Unit;
import soot.UnitPatchingChain;
import soot.Value;
import soot.jimple.Constant;
import soot.jimple.IntConstant;
import soot.jimple.StringConstant;
import soot.jimple.internal.JAddExpr;
import soot.jimple.internal.JAssignStmt;
import soot.jimple.internal.JDivExpr;
import soot.jimple.internal.JIfStmt;
import soot.jimple.internal.JMulExpr;
import soot.jimple.internal.JSubExpr;
import soot.jimple.internal.JimpleLocal;
import soot.shimple.ShimpleBody;
import soot.shimple.internal.SPhiExpr;
import soot.shimple.toolkits.scalar.ShimpleLocalDefs;
import soot.shimple.toolkits.scalar.ShimpleLocalUses;
import soot.toolkits.scalar.UnitValueBoxPair;
import soot.util.Chain;

public class AnalysisTransformer extends BodyTransformer {
    AnalysisTransformer(String optimization){
        this.optimization = optimization;
    }

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

        if (optimization == "scp"){
            SimpleConstPropagation scp = new SimpleConstPropagation(body);
        } else {
            SparseConditionalConstPropagation sccp = new SparseConditionalConstPropagation(body);
        }
    }

    private String optimization;    // the desired optimization
}

/*
 * Reference: https://people.iith.ac.in/ramakrishna/fc5264/ssa-intro-construct.pdf
 * A very simple constant folder and propagation
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
        for (Unit u : units){
            replaceWith.put(u, u);
        }

        LinkedList<Unit> q = new LinkedList<>();
        q.addAll(units);

        // process each statement
        while (!q.isEmpty()){
            Unit u = q.pollFirst();
            Unit changedU = replaceWith.get(u);

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

                if (Utils.isIntConstant(rhs)){       // x = c
                    // propagate the constant to all uses
                    for (Object useValueBox : localUses.getUsesOf(u)){
                        UnitValueBoxPair pair = (UnitValueBoxPair) useValueBox;
                        Unit use = pair.getUnit();

                        // simplify the use with the value of this unit's local
                        replaceWith.put(
                            use, 
                            simplify(replaceWith.get(use), local, (IntConstant) rhs)
                        );

                        q.add(use);
                    }
                } 
                        
            } 
            // System.out.println(localUses.getUsesOf(u));
        }

        System.out.println(sep + sep + sep);
        // System.out.println(body.toJimpleBody());
        for (Unit u : units){
            printObject(replaceWith.get(u));
        }
    }

    private Unit simplify(Unit u, JimpleLocal var, IntConstant value){
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
                    if (Utils.isLocal(arg)
                        && (var.equivTo((JimpleLocal) arg))){
                        
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

                if (Utils.isLocal(left)
                    && var.equivTo((JimpleLocal) left)){
                    expr.setOp1(value);
                    left = value;
                }

                if (Utils.isLocal(right)
                    && var.equivTo((JimpleLocal) right)){
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

                if (Utils.isLocal(left)
                    && var.equivTo((JimpleLocal) left)){
                    expr.setOp1(value);
                    left = value;
                }

                if (Utils.isLocal(right)
                    && var.equivTo((JimpleLocal) right)){
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

                if (Utils.isLocal(left)
                    && var.equivTo((JimpleLocal) left)){
                    expr.setOp1(value);
                    left = value;
                }

                if (Utils.isLocal(right)
                    && var.equivTo((JimpleLocal) right)){
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

                if (Utils.isLocal(left)
                    && var.equivTo((JimpleLocal) left)){
                    expr.setOp1(value);
                    left = value;
                }

                if (Utils.isLocal(right)
                    && var.equivTo((JimpleLocal) right)){
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
        }

        return u;
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

    public static int extractIntValue(Value v){
        IntConstant c = (IntConstant) v;
        return c.value;
    }
}