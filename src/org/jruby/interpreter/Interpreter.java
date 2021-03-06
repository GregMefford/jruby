package org.jruby.interpreter;

import java.util.List;

import org.jruby.Ruby;
import org.jruby.RubyInstanceConfig;
import org.jruby.RubyModule;
import org.jruby.RubyProc;
import org.jruby.ast.Node;
import org.jruby.ast.RootNode;
import org.jruby.compiler.ir.IRBuilder;
import org.jruby.compiler.ir.IRMethod;
import org.jruby.compiler.ir.IREvalScript;
import org.jruby.compiler.ir.IRScope;
import org.jruby.compiler.ir.IRClosure;
import org.jruby.compiler.ir.IRScriptBody;
import org.jruby.compiler.ir.instructions.CallBase;
import org.jruby.compiler.ir.instructions.CopyInstr;
import org.jruby.compiler.ir.instructions.JumpInstr;
import org.jruby.compiler.ir.instructions.JumpIndirectInstr;
import org.jruby.compiler.ir.instructions.ReceiveArgBase;
import org.jruby.compiler.ir.instructions.ReceiveArgumentInstruction;
import org.jruby.compiler.ir.instructions.ReceiveOptArgBase;
import org.jruby.compiler.ir.instructions.ReceiveRestArgBase;
import org.jruby.compiler.ir.instructions.LineNumberInstr;
import org.jruby.compiler.ir.instructions.ReturnInstr;
import org.jruby.compiler.ir.instructions.ClosureReturnInstr;
import org.jruby.compiler.ir.instructions.BreakInstr;
import org.jruby.compiler.ir.instructions.BEQInstr;
import org.jruby.compiler.ir.instructions.BNEInstr;
import org.jruby.compiler.ir.instructions.BranchInstr;
import org.jruby.compiler.ir.instructions.Instr;
import org.jruby.compiler.ir.instructions.ResultInstr;
import org.jruby.compiler.ir.instructions.jruby.CheckArityInstr;
import org.jruby.compiler.ir.instructions.ruby19.ReceiveRequiredArgInstr;
import org.jruby.compiler.ir.operands.IRException;
import org.jruby.compiler.ir.operands.Label;
import org.jruby.compiler.ir.operands.Nil;
import org.jruby.compiler.ir.operands.Operand;
import org.jruby.compiler.ir.operands.Variable;
import org.jruby.compiler.ir.operands.LocalVariable;
import org.jruby.compiler.ir.operands.TemporaryVariable;
import org.jruby.compiler.ir.operands.UndefinedValue;
import org.jruby.compiler.ir.operands.WrappedIRClosure;
import org.jruby.exceptions.RaiseException;
import org.jruby.exceptions.Unrescuable;
import org.jruby.parser.IRStaticScope;
import org.jruby.parser.StaticScope;
import org.jruby.internal.runtime.methods.InterpretedIRMethod;
import org.jruby.parser.IRStaticScopeFactory;
import org.jruby.runtime.Block;
import org.jruby.runtime.Block.Type;
import org.jruby.runtime.RubyEvent;
import org.jruby.runtime.Arity;
import org.jruby.runtime.DynamicScope;
import org.jruby.runtime.ThreadContext;
import org.jruby.runtime.builtin.IRubyObject;
import org.jruby.util.log.Logger;
import org.jruby.util.log.LoggerFactory;
import org.jruby.util.unsafe.UnsafeFactory;

public class Interpreter {
    private static final Logger LOG = LoggerFactory.getLogger("Interpreter");

    private static int interpInstrsCount = 0;

    public static boolean isDebug() {
        return RubyInstanceConfig.IR_DEBUG;
    }

    private static IRScope getEvalContainerScope(Ruby runtime, StaticScope evalScope) {
        // SSS FIXME: Weirdness here.  We cannot get the containing IR scope from evalScope because of static-scope wrapping
        // that is going on
        // 1. In all cases, DynamicScope.getEvalScope wraps the executing static scope in a new local scope.
        // 2. For instance-eval (module-eval, class-eval) scenarios, there is an extra scope that is added to 
        //    the stack in ThreadContext.java:preExecuteUnder
        // I dont know what rule to apply when.  However, in both these cases, since there is no IR-scope associated,
        // I have used the hack below where I first unwrap once and see if I get a non-null IR scope.  If that doesn't
        // work, I unwarp once more and I am guaranteed to get the IR scope I want.
        IRScope containingIRScope = ((IRStaticScope)evalScope.getEnclosingScope()).getIRScope();
        if (containingIRScope == null) containingIRScope = ((IRStaticScope)evalScope.getEnclosingScope().getEnclosingScope()).getIRScope();
        return containingIRScope;
    }

    private static IRScope getEvalContainerScope19(Ruby runtime, StaticScope evalScope) {
        // SSS FIXME: Weirdness here.  Different from 1.8.  There is no localvar scope wrapping.
        // 1. For instance-eval (module-eval, class-eval) scenarios, there is an extra scope that is added to 
        //    the stack in ThreadContext.java:preExecuteUnder
        // I dont know what rule to apply when.  However, in both these cases, since there is no IR-scope associated,
        // I have used the hack below where I first unwrap once and see if I get a non-null IR scope.  If that doesn't
        // work, I unwarp once more and I am guaranteed to get the IR scope I want.
        IRScope containingIRScope = ((IRStaticScope)evalScope).getIRScope();
        if (containingIRScope == null) containingIRScope = ((IRStaticScope)evalScope.getEnclosingScope()).getIRScope();
        if (containingIRScope == null) containingIRScope = ((IRStaticScope)evalScope.getEnclosingScope().getEnclosingScope()).getIRScope();
        return containingIRScope;
    }

    public static IRubyObject interpretCommonEval(Ruby runtime, String file, int lineNumber, String backtraceName, RootNode rootNode, IRubyObject self, Block block) {
        boolean is_1_9 = runtime.is1_9();
        // SSS FIXME: Is this required here since the IR version cannot change from eval-to-eval? This is much more of a global setting.
        if (is_1_9) IRBuilder.setRubyVersion("1.9");

        StaticScope ss = rootNode.getStaticScope();
        IRScope containingIRScope = is_1_9 ? getEvalContainerScope19(runtime, ss) : getEvalContainerScope(runtime, ss);
        IREvalScript evalScript = IRBuilder.createIRBuilder(runtime.getIRManager()).buildEvalRoot(ss, containingIRScope, file, lineNumber, rootNode);
        evalScript.prepareForInterpretation();
//        evalScript.runCompilerPass(new CallSplitter());
        ThreadContext context = runtime.getCurrentContext(); 
        runBeginEndBlocks(evalScript.getBeginBlocks(), context, self, null); // FIXME: No temp vars yet right?
        IRubyObject rv = evalScript.call(context, self, evalScript.getStaticScope().getModule(), rootNode.getScope(), block, backtraceName);
        runBeginEndBlocks(evalScript.getEndBlocks(), context, self, null); // FIXME: No temp vars right?
        return rv;
    }

    public static IRubyObject interpretSimpleEval(Ruby runtime, String file, int lineNumber, String backtraceName, Node node, IRubyObject self) {
        return interpretCommonEval(runtime, file, lineNumber, backtraceName, (RootNode)node, self, Block.NULL_BLOCK);
    }

    public static IRubyObject interpretBindingEval(Ruby runtime, String file, int lineNumber, String backtraceName, Node node, IRubyObject self, Block block) {
        return interpretCommonEval(runtime, file, lineNumber, backtraceName, (RootNode)node, self, block);
    }

    public static void runBeginEndBlocks(List<IRClosure> beBlocks, ThreadContext context, IRubyObject self, Object[] temp) {
        if (beBlocks == null) return;

        for (IRClosure b: beBlocks) {
            // SSS FIXME: Should I piggyback on WrappedIRClosure.retrieve or just copy that code here?
            b.prepareForInterpretation();
            Block blk = (Block)(new WrappedIRClosure(b)).retrieve(context, self, context.getCurrentScope(), temp);
            blk.yield(context, null);
        }
    }

    public static IRubyObject interpret(Ruby runtime, Node rootNode, IRubyObject self) {
        if (runtime.is1_9()) IRBuilder.setRubyVersion("1.9");

        IRScriptBody root = (IRScriptBody) IRBuilder.createIRBuilder(runtime.getIRManager()).buildRoot((RootNode) rootNode);

        // We get the live object ball rolling here.  This give a valid value for the top
        // of this lexical tree.  All new scope can then retrieve and set based on lexical parent.
        if (root.getStaticScope().getModule() == null) { // If an eval this may already be setup.
            root.getStaticScope().setModule(runtime.getObject());
        }

        RubyModule currModule = root.getStaticScope().getModule();

        // Scope state for root?
        IRStaticScopeFactory.newIRLocalScope(null).setModule(currModule);
        ThreadContext context = runtime.getCurrentContext();

        try {
            runBeginEndBlocks(root.getBeginBlocks(), context, self, null); // FIXME: No temp vars yet...not needed?
            InterpretedIRMethod method = new InterpretedIRMethod(root, currModule);
            IRubyObject rv =  method.call(context, self, currModule, "(root)", IRubyObject.NULL_ARRAY);
            runBeginEndBlocks(root.getEndBlocks(), context, self, null); // FIXME: No temp vars yet...not needed?
            if (isDebug()) LOG.info("-- Interpreted instructions: {}", interpInstrsCount);
            return rv;
        } catch (IRBreakJump bj) {
            throw IRException.BREAK_LocalJumpError.getException(context.getRuntime());
        }
    }

    private static IRubyObject interpret(ThreadContext context, IRubyObject self, 
            IRScope scope, IRubyObject[] args, Block block, Block.Type blockType) {
        boolean debug = isDebug();
        boolean inClosure = (scope instanceof IRClosure);
        Instr[] instrs = scope.getInstrsForInterpretation();

        // The base IR may not have been processed yet
        if (instrs == null) instrs = scope.prepareForInterpretation();

        int temporaryVariablesSize = scope.getTemporaryVariableSize();
        Object[] temp = temporaryVariablesSize > 0 ? new Object[temporaryVariablesSize] : null;
        int n   = instrs.length;
        int ipc = 0;
        Instr lastInstr = null;
        IRubyObject rv = null;
        Object exception = null;
        Ruby runtime = context.getRuntime();
        DynamicScope currDynScope = context.getCurrentScope();
        while (ipc < n) {
            lastInstr = instrs[ipc];
            if (debug) {
                LOG.info("I: {}", lastInstr);
                interpInstrsCount++;
            }

            // We need a nested try-catch:
            // - The first try-catch around the instruction captures JRuby-implementation exceptions
            //   generated by return and break instructions.  This catch could then raise Ruby-visible
            //   LocalJump errors which could be caught by Ruby-level exception handlers.
            // - The second try-catch around the first try-catch handles Ruby-visible exceptions and
            //   invokes Ruby-level exceptions handlers.
            try {
                Variable resultVar = null;
                Object result = null;
                try {
                    switch(lastInstr.getOperation()) {
                    case JUMP: {
                        ipc = ((JumpInstr)lastInstr).getJumpTarget().getTargetPC();
                        break;
                    }
                    case JUMP_INDIRECT: {
                        ipc = ((Label)((JumpIndirectInstr)lastInstr).getJumpTarget().retrieve(context, self, currDynScope, temp)).getTargetPC();
                        break;
                    }
                    case B_TRUE: {
                        BranchInstr br = (BranchInstr)lastInstr;
                        Object value1 = br.getArg1().retrieve(context, self, currDynScope, temp);
                        ipc = ((IRubyObject)value1).isTrue()? br.getJumpTarget().getTargetPC() : ipc+1;
                        break;
                    }
                    case B_FALSE: {
                        BranchInstr br = (BranchInstr)lastInstr;
                        Object value1 = br.getArg1().retrieve(context, self, currDynScope, temp);
                        ipc = !((IRubyObject)value1).isTrue()? br.getJumpTarget().getTargetPC() : ipc+1;
                        break;
                    }
                    case B_NIL: {
                        BranchInstr br = (BranchInstr)lastInstr;
                        Object value1 = br.getArg1().retrieve(context, self, currDynScope, temp);
                        ipc = value1 == context.nil ? br.getJumpTarget().getTargetPC() : ipc+1;
                        break;
                    }
                    case B_UNDEF: {
                        BranchInstr br = (BranchInstr)lastInstr;
                        Object value1 = br.getArg1().retrieve(context, self, currDynScope, temp);
                        ipc = value1 == UndefinedValue.UNDEFINED ? br.getJumpTarget().getTargetPC() : ipc+1;
                        break;
                    }
                    case BEQ: {
                        BEQInstr beq = (BEQInstr)lastInstr;
                        Object value1 = beq.getArg1().retrieve(context, self, currDynScope, temp);
                        Object value2 = beq.getArg2().retrieve(context, self, currDynScope, temp);
                        boolean eql = ((IRubyObject) value1).op_equal(context, (IRubyObject)value2).isTrue();
                        ipc = eql ? beq.getJumpTarget().getTargetPC() : ipc+1;
                        break;
                    }
                    case BNE: {
                        BNEInstr bne = (BNEInstr)lastInstr;
                        Operand arg1 = bne.getArg1();
                        Operand arg2 = bne.getArg2();
                        Object value1 = arg1.retrieve(context, self, currDynScope, temp);
                        Object value2 = arg2.retrieve(context, self, currDynScope, temp);
                        boolean eql = ((arg2 == Nil.NIL) || (arg2 == UndefinedValue.UNDEFINED)) ?
                                       value1 == value2 : ((IRubyObject) value1).op_equal(context, (IRubyObject)value2).isTrue();
                        ipc = !eql ? bne.getJumpTarget().getTargetPC() : ipc+1;
                        break;
                    }
                    case RECV_ARG: {
                        ReceiveArgumentInstruction ra = (ReceiveArgumentInstruction)lastInstr;
                        int argIndex = ra.getArgIndex();
                        result = (argIndex < args.length) ? args[argIndex] : context.nil; // SSS FIXME: This check is only required for closures, not methods
                        resultVar = ra.getResult();
                        ipc++;
                        break;
                    }
                    case RECV_REQD_ARG: {
                        ReceiveRequiredArgInstr ra = (ReceiveRequiredArgInstr)lastInstr;
                        result = ra.receiveRequiredArg(args);
                        if (result == null) result = context.nil; // For blocks
                        resultVar = ra.getResult();
                        ipc++;
                        break;
                    }
                    case RECV_OPT_ARG: {
                        ReceiveOptArgBase ra = (ReceiveOptArgBase)lastInstr;
                        result = ra.receiveOptArg(args);
                        resultVar = ra.getResult();
                        ipc++;
                        break;
                    }
                    case RECV_REST_ARG: {
                        ReceiveRestArgBase ra = (ReceiveRestArgBase)lastInstr;
                        result = ra.receiveRestArg(runtime, args);
                        resultVar = ra.getResult();
                        ipc++;
                        break;
                    }
                    case RECV_CLOSURE: {
                        result = block == Block.NULL_BLOCK ? context.nil : runtime.newProc(Block.Type.PROC, block);
                        resultVar = ((ResultInstr)lastInstr).getResult();
                        ipc++;
                        break;
                    }
                    case RECV_EXCEPTION: {
                        result = exception;
                        resultVar = ((ResultInstr)lastInstr).getResult();
                        ipc++;
                        break;
                    }
                    case ATTR_ASSIGN:
                    case CALL: {
                        CallBase c = (CallBase)lastInstr;
                        IRubyObject object = (IRubyObject)c.getReceiver().retrieve(context, self, currDynScope, temp);
                        Object callResult = c.getCallAdapter().call(context, self, object, currDynScope, temp);
                        if (c instanceof ResultInstr) {
                            result = callResult;
                            resultVar = ((ResultInstr)c).getResult();
                        }
                        ipc++;
                        break;
                    }
                    case RETURN: {
                        rv = (IRubyObject)((ReturnInstr)lastInstr).getReturnValue().retrieve(context, self, currDynScope, temp);
                        ipc = n;
                        break;
                    }
                    case CLOSURE_RETURN: {
                        rv = (IRubyObject)((ClosureReturnInstr)lastInstr).getReturnValue().retrieve(context, self, currDynScope, temp);
                        ipc = n;
                        break;
                    }
                    case THREAD_POLL: {
                        context.callThreadPoll();
                        ipc++;
                        break;
                    }
                    case LINE_NUM: {
                        context.setLine(((LineNumberInstr)lastInstr).lineNumber);
                        ipc++;
                        break;
                    }
                    case COPY: {
                        CopyInstr c = (CopyInstr)lastInstr;
                        result = c.getSource().retrieve(context, self, currDynScope, temp);
                        resultVar = ((ResultInstr)lastInstr).getResult();
                        ipc++;
                        break;
                    }
                    case CHECK_ARITY: {
                        CheckArityInstr ca = (CheckArityInstr)lastInstr;
                        int numArgs = args.length;
                        if ((numArgs < ca.required) || ((ca.rest == -1) && (numArgs > (ca.required + ca.opt)))) {
                            Arity.raiseArgumentError(runtime, numArgs, ca.required, ca.required + ca.opt);
                        }
                         ipc++;
                        break;
                    }
                    default: {
                        result = lastInstr.interpret(context, currDynScope, self, temp, block);
                        if (lastInstr instanceof ResultInstr) resultVar = ((ResultInstr)lastInstr).getResult();
                        ipc++;
                        break;
                    }
                    }

                    if (resultVar != null) {
                        if (resultVar instanceof TemporaryVariable) {
                            temp[((TemporaryVariable)resultVar).offset] = result;
                        }
                        else {
                            LocalVariable lv = (LocalVariable)resultVar;
                            currDynScope.setValue((IRubyObject) result, lv.getLocation(), lv.getScopeDepth());
                        }
                    }
                } catch (IRReturnJump rj) {
                    ipc = scope.getEnsurerPC(lastInstr);
                    // Because of yields, control can exit this scope via returns in the block being yielded to.
                    // Before we exit this scope, we have to run ensure blocks in this scope.
                    //
                    // In the current IR design, the IR builder cannot generate explicit IR instructions that
                    // trap the return and run the ensure code.  Hence, we have to implicitly handle this in the
                    // interpreter.
                    if (ipc == -1) {
                        // No ensure block here, propagate the return
                        return handleReturnJumpInClosure(scope, rj, blockType);
                    } else {
                        // Set the return jump as the exception to the ensure block and continue
                        // The ensure block will rethrow this exception at which time control
                        // will go the if-block above and the return jump will get handled properly
                        exception = rj;
                    }
                } catch (IRBreakJump bj) {
                    if ((lastInstr instanceof BreakInstr) || bj.breakInEval) {
                        handleBreakJump(context, scope, bj, self, blockType, inClosure);
                    } else if (inLambda(blockType)) {
                        // We just unwound all the way up because of a non-local break
                        throw IRException.BREAK_LocalJumpError.getException(runtime);
                    } else if (bj.caughtByLambda || (bj.scopeToReturnTo == scope)) {
                        // We got where we need to get to (because a lambda stopped us, or because we popped to the
                        // lexical scope where we got called from).  Retrieve the result and store it.

                        // SSS FIXME: why cannot I just use resultVar from the loop above?? why did it break something?
                        if (lastInstr instanceof ResultInstr) {
                            resultVar = ((ResultInstr) lastInstr).getResult();
                            if (resultVar instanceof TemporaryVariable) {
                                temp[((TemporaryVariable)resultVar).offset] = bj.breakValue;
                            }
                            else {
                                LocalVariable lv = (LocalVariable)resultVar;
                                currDynScope.setValue((IRubyObject) bj.breakValue, lv.getLocation(), lv.getScopeDepth());
                            }
                        }
                        ipc += 1;
                    } else {
                        // We need to continue to break upwards.
                        // Run any ensures we need to run before breaking up. 
                        // Quite easy to do this by passing 'bj' as the exception to the ensure block!
                        ipc = scope.getEnsurerPC(lastInstr);
                        if (ipc == -1) throw bj; // No ensure block here, just rethrow bj
                        exception = bj; // Found an ensure block, set 'bj' as the exception and transfer control
                    }
                }
            } catch (RaiseException re) {
                if (debug) LOG.info("in scope: " + scope + ", caught raise exception: " + re.getException() + "; excepting instr: " + lastInstr);
                ipc = scope.getRescuerPC(lastInstr);
                if (debug) LOG.info("ipc for rescuer: " + ipc);
                if (ipc == -1) throw re; // No one rescued exception, pass it on!

                exception = re.getException();
                // SSS: Copied this comment and line from ast/RescueNode.java:handleException
                // TODO: Rubicon TestKernel dies without this line.  A cursory glance implies we
                // falsely set $! to nil and this sets it back to something valid.  This should 
                // get fixed at the same time we address bug #1296484.
                runtime.getGlobalVariables().set("$!", (IRubyObject)exception);
            } catch (Throwable t) {
                if (t instanceof Unrescuable) {
                    // ThreadKill, RubyContinuation, MainExitException, etc.
                    // these cannot be rescued -- only run ensure blocks
                    ipc = scope.getEnsurerPC(lastInstr);
                } else {
                    // Error and other java exceptions which could be rescued
                    if (debug) LOG.info("in scope: " + scope + ", caught Java throwable: " + t + "; excepting instr: " + lastInstr);
                    ipc = scope.getRescuerPC(lastInstr);
                    if (debug) LOG.info("ipc for rescuer: " + ipc);
                }
                if (ipc == -1) UnsafeFactory.getUnsafe().throwException(t); // No ensure block here, pass it on! 
                exception = t;
            }
        }

        // If not in a lambda, and lastInstr was a return, check if this was a non-local return
        if ((lastInstr instanceof ReturnInstr) && !inLambda(blockType)) {
            handleNonLocalReturn(context, scope, (ReturnInstr) lastInstr, rv, inClosure);
        }

        return rv;
    }

    /*
     * Handle non-local returns (ex: when nested in closures, root scopes of module/class/sclass bodies)
     */
    private static void handleNonLocalReturn(ThreadContext context, IRScope scope, ReturnInstr returnInstr, IRubyObject returnValue, boolean inClosure) {
        IRMethod methodToReturnFrom = returnInstr.methodToReturnFrom;

        if (inClosure) {
            if (methodToReturnFrom == null) {
                if (context.getThread() == context.getRuntime().getThreadService().getMainThread()) {
                    throw IRException.RETURN_LocalJumpError.getException(context.getRuntime());
                } else {
                    throw context.getRuntime().newThreadError("return can't jump across threads");
                }
            }

            // Cannot return to the call that we have long since exited.
            if (!context.scopeExistsOnCallStack(methodToReturnFrom.getStaticScope())) {
                if (isDebug()) LOG.info("in scope: " + scope + ", raising unexpected return local jump error");
                throw IRException.RETURN_LocalJumpError.getException(context.getRuntime());
            }

            throw IRReturnJump.create(methodToReturnFrom, returnValue);
        } else if ((methodToReturnFrom != null)) {
            // methodtoReturnFrom will not be null for explicit returns from class/module/sclass bodies
            throw IRReturnJump.create(methodToReturnFrom, returnValue);
        }        
    }

    private static IRubyObject handleReturnJumpInClosure(IRScope scope, IRReturnJump rj, Type blockType) throws IRReturnJump {
        // - If we are in a lambda or if we are in the method scope we are supposed to return from, stop propagating
        if (inNonMethodBodyLambda(scope, blockType) || (rj.methodToReturnFrom == scope)) return (IRubyObject) rj.returnValue;

        // - If not, Just pass it along!
        throw rj;
    }

    private static void handleBreakJump(ThreadContext context, IRScope scope, IRBreakJump bj, IRubyObject self, Type blockType, boolean inClosure) throws RaiseException, IRBreakJump {
        bj.breakInEval = false;  // Clear eval flag

        // Error
        if (!inClosure) throw IRException.BREAK_LocalJumpError.getException(context.getRuntime());

        if (inProc(blockType)) {
            // SSS FIXME: Here we need to check if the current executing block has escaped
            // which means the block has to be passed in from Block.call -> BlockBody.call -> Interpreter.interpret
        } else if (inLambda(blockType)) {
            bj.caughtByLambda = true;
        } else if (scope instanceof IREvalScript) {
            // If we are in an eval, record it so we can account for it
            bj.breakInEval = true;
        }

        // Pass it upward
        throw bj;
    }

    public static IRubyObject INTERPRET_EVAL(ThreadContext context, IRubyObject self, 
            IRScope scope, IRubyObject[] args, String name, Block block, Block.Type blockType) {
        try {
            ThreadContext.pushBacktrace(context, name, scope.getFileName(), context.getLine());
            return interpret(context, self, scope, args, block, blockType);
        } finally {
            ThreadContext.popBacktrace(context);
        }
    }

    public static IRubyObject INTERPRET_BLOCK(ThreadContext context, IRubyObject self, 
            IRScope scope, IRubyObject[] args, String name, Block block, Block.Type blockType) {
        try {
            ThreadContext.pushBacktrace(context, name, scope.getFileName(), context.getLine());
            return interpret(context, self, scope, args, block, blockType);
        } finally {
            ThreadContext.popBacktrace(context);
        }
    }

    public static IRubyObject INTERPRET_METHOD(ThreadContext context, IRScope scope, 
        IRubyObject self, String name, RubyModule implClass, IRubyObject[] args, Block block, Block.Type blockType, boolean isTraceable) {
        Ruby runtime = context.getRuntime();
        boolean syntheticMethod = name == null || name.equals("");

        try {
            if (!syntheticMethod) ThreadContext.pushBacktrace(context, name, scope.getFileName(), context.getLine());
            if (isTraceable) methodPreTrace(runtime, context, name, implClass);
            return interpret(context, self, scope, args, block, blockType);
        } finally {
            if (isTraceable) {
                try {methodPostTrace(runtime, context, name, implClass);}
                finally { if (!syntheticMethod) ThreadContext.popBacktrace(context);}
            } else {
                if (!syntheticMethod) ThreadContext.popBacktrace(context);
            }
        }
    }
    
    private static boolean inNonMethodBodyLambda(IRScope scope, Block.Type blockType) {
        // SSS FIXME: Hack! AST interpreter and JIT compiler marks a proc's static scope as
        // an argument scope if it is used to define a method's body via :define_method.
        // Since that is exactly what we want to figure out here, am just using that flag here.
        // But, this is ugly (as is the original hack in the current runtime).  What is really
        // needed is a new block type -- a block that is used to define a method body.
        return blockType == Block.Type.LAMBDA && !scope.getStaticScope().isArgumentScope();
    }
    
    private static boolean inLambda(Block.Type blockType) {
        return blockType == Block.Type.LAMBDA;
    }

    public static boolean inProc(Block.Type blockType) {
        return blockType == Block.Type.PROC;
    }    

    private static void methodPreTrace(Ruby runtime, ThreadContext context, String name, RubyModule implClass) {
        if (runtime.hasEventHooks()) context.trace(RubyEvent.CALL, name, implClass);
    }

    private static void methodPostTrace(Ruby runtime, ThreadContext context, String name, RubyModule implClass) {
        if (runtime.hasEventHooks()) context.trace(RubyEvent.RETURN, name, implClass);
    }
}
