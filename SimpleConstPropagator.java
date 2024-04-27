import soot.*;
import soot.jimple.*;
import soot.jimple.internal.*;
import soot.shimple.*;
import soot.shimple.internal.*;
import soot.shimple.toolkits.scalar.*;
import soot.toolkits.scalar.UnitValueBoxPair;

import java.util.*;
import soot.util.*;

/*
 * Reference: https://people.iith.ac.in/ramakrishna/fc5264/ssa-intro-construct.pdf
 * A very simple constant folder and propagator
 */
public class SimpleConstPropagator{
    SimpleConstPropagator(Body body){
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
            replaceWith.put(u, (Unit) u.clone());
        }

        HashSet<Unit> deadStmts = new HashSet<>();

        LinkedList<Unit> q = new LinkedList<>();
        q.addAll(units);

        // process each statement
        while (!q.isEmpty()){
            Unit u = q.pollFirst();
            Unit changedU = replaceWith.get(u);

            if (Utils.isAssignmentStmt(changedU)){
                JAssignStmt stmt = (JAssignStmt) changedU;
                Value lhs = stmt.getLeftOp();
                
                // can't handle field ref
                if (!Utils.isLocal(lhs))
                    continue;
                
                JimpleLocal local = (JimpleLocal) lhs;
                Value rhs = stmt.getRightOp();
                
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

            // printObject(stmt);
            // if branch then replace if with goto to branch target
            if (branch)
                return Jimple.v().newGotoStmt(stmt.getTargetBox());
            else if (fallThrough)       // otherwise we fall through so delete if stmt
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

    private ShimpleBody body;
}
