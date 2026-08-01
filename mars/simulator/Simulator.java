   package mars.simulator;
   import mars.*;
   import mars.venus.*;
   import mars.util.*;
   import mars.mips.hardware.*;
   import mars.mips.instructions.*;
   import java.util.*;
   import javax.swing.*;
   import java.awt.event.*;
   // P7 timer imports
   import mars.mips.hardware.TimerOne;
   import mars.mips.hardware.TimerTwo;
	
	/*
Copyright (c) 2003-2010,  Pete Sanderson and Kenneth Vollmar

Developed by Pete Sanderson (psanderson@otterbein.edu)
and Kenneth Vollmar (kenvollmar@missouristate.edu)

Permission is hereby granted, free of charge, to any person obtaining 
a copy of this software and associated documentation files (the 
"Software"), to deal in the Software without restriction, including 
without limitation the rights to use, copy, modify, merge, publish, 
distribute, sublicense, and/or sell copies of the Software, and to 
permit persons to whom the Software is furnished to do so, subject 
to the following conditions:

The above copyright notice and this permission notice shall be 
included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, 
EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF 
MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. 
IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR 
ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF 
CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION 
WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

(MIT license, http://www.opensource.org/licenses/mit-license.html)
 */
	
/**
 * Used to simulate the execution of an assembled MIPS program.
 * @author Pete Sanderson
 * @version August 2005
 **/

    public class Simulator extends Observable {
      private SimThread simulatorThread;
      private static Simulator simulator = null;  // Singleton object
      private static Runnable interactiveGUIUpdater = null;
      private volatile Integer courseHaltAddress = null;
      private volatile boolean courseHaltDelaySlotPending = false;
      private volatile int courseP7KernelTextEnd = 0x0000417C;
      private volatile int lastTerminationReason = 0;
      // Others can set this true to indicate external interrupt.  Initially used
   	// to simulate keyboard and display interrupts.  The device is identified
   	// by the address of its MMIO control register.  keyboard 0xFFFF0000 and
   	// display 0xFFFF0008.  DPS 23 July 2008.
      public static final int NO_DEVICE = 0;
      public static volatile int externalInterruptingDevice = NO_DEVICE;
   	/** various reasons for simulate to end... */
      public static final int BREAKPOINT = 1;
      public static final int EXCEPTION  = 2;
      public static final int MAX_STEPS  = 3;  // includes step mode (where maxSteps is 1)
      public static final int NORMAL_TERMINATION = 4;
      public static final int CLIFF_TERMINATION = 5; // run off bottom of program
      public static final int PAUSE_OR_STOP = 6;
      public static final int COURSE_HALT = 7;
      private static final int COURSE_TEXT_BASE = 0x00003000;
      private static final int COURSE_TEXT_LIMIT = 0x00006FFC;
      private static final int COURSE_TEXT_EXCLUSIVE_LIMIT = 0x00007000;
      private static final int COURSE_P7_KERNEL_TEXT_BASE = 0x00004180;
   
      /**
   	 * Returns the Simulator object
   	 *
   	 * @return the Simulator object in use
   	 */
       public static Simulator getInstance() {
         // Do NOT change this to create the Simulator at load time (in declaration above)!
      	// Its constructor looks for the GUI, which at load time is not created yet,
      	// and incorrectly leaves interactiveGUIUpdater null!  This causes runtime
      	// exceptions while running in timed mode.
         if (simulator==null) {
            simulator = new Simulator();
         }
         return simulator;
      }
   
       private Simulator() {
         simulatorThread = null;
         if (Globals.getGui() != null) {
            interactiveGUIUpdater = new UpdateGUI();
         } 
      }

      /**
       * Configure an optional command-line course halt target for the next run.
       * Passing null disables tracking.  The caller validates the instruction
       * pair before simulation.  For P7, this also snapshots the contiguous
       * handler prefix that the plugin dumps beginning at 0x4180.  Completion
       * is recorded only after the branch itself (no delay slots) or its nop
       * delay slot (delayed branching) has executed successfully after
       * interrupt/exception prechecks.
       */
       public void configureCourseHalt(Integer address) {
         courseHaltAddress = address;
         courseHaltDelaySlotPending = false;
         courseP7KernelTextEnd = COURSE_P7_KERNEL_TEXT_BASE - Instruction.INSTRUCTION_LENGTH;
         if (address != null && Globals.getSettings().getExceptionForCourse()) {
            try {
               // Match the plugin's kernel dump: only the contiguous prefix
               // beginning at 0x4180 is loaded into the DUT instruction memory.
               courseP7KernelTextEnd = Globals.memory.getAddressOfFirstNull(
                  COURSE_P7_KERNEL_TEXT_BASE, COURSE_TEXT_EXCLUSIVE_LIMIT) -
                  Instruction.INSTRUCTION_LENGTH;
            }
            catch (AddressErrorException aee) {
               // Constants above are word-aligned.  Treat an unexpected memory
               // configuration failure as an empty loaded handler interval.
               courseP7KernelTextEnd = COURSE_P7_KERNEL_TEXT_BASE -
                  Instruction.INSTRUCTION_LENGTH;
            }
         }
         lastTerminationReason = 0;
      }

       public int getLastTerminationReason() {
         return lastTerminationReason;
      }

       public boolean isCourseP7TestContractEnabled() {
         return courseHaltAddress != null &&
            Globals.getSettings().getExceptionForCourse() &&
            Globals.getSettings().getStrictDataAccess();
      }

       public boolean isLoadedCourseP7HandlerAddress(int address) {
         return address >= COURSE_P7_KERNEL_TEXT_BASE &&
            address <= courseP7KernelTextEnd;
      }

       public void validateCourseP7InterruptGeneratorAccess(
         ProgramStatement statement, int address, int length, int exceptionCause)
         throws ProcessingException {
         if (!isCourseP7TestContractEnabled()) {
            return;
         }
         long endAddress = (long) address + (long) length - 1L;
         if (endAddress < 0x00007F20L || address > 0x00007F23) {
            return;
         }
         int instructionAddress = RegisterFile.getProgramCounter() -
            Instruction.INSTRUCTION_LENGTH;
         boolean exactResponseInstruction =
            exceptionCause == Exceptions.ADDRESS_EXCEPTION_STORE &&
            address == 0x00007F20 && length == 1 &&
            statement.getBinaryStatement() == 0xA0007F20 &&
            isLoadedCourseP7HandlerAddress(instructionAddress);
         if (!exactResponseInstruction) {
            throw ProcessingException.courseContractViolation(statement,
               "interrupt-generator access at " + Binary.intToHexString(address) +
               " from PC " + Binary.intToHexString(instructionAddress) +
               " must be the loaded-handler instruction 0xa0007f20.");
         }
      }
   
   
   
   /**
    *  Determine whether or not the next instruction to be executed is in a
    *  "delay slot".  This means delayed branching is enabled, the branch
    *  condition has evaluated true, and the next instruction executed will
    *  be the one following the branch.  It is said to occupy the "delay slot."
    *  Normally programmers put a nop instruction here but it can be anything.  
    *
    *  @return true if next instruction is in delay slot, false otherwise.
    */
   
       public static boolean inDelaySlot() {
         return DelayedBranch.isTriggered();
      }	
   
   
   /**
    * Simulate execution of given MIPS program.  It must have already been assembled.
    * @param p The MIPSprogram to be simulated.
    * @param pc address of first instruction to simulate; this goes into program counter
    * @param maxSteps maximum number of steps to perform before returning false (0 or less means no max)
    * @param breakPoints array of breakpoint program counter values, use null if none
    * @param actor the GUI component responsible for this call, usually GO or STEP.  null if none.
    * @return true if execution completed, false otherwise
    * @throws ProcessingException Throws exception if run-time exception occurs.
    **/
    
       public boolean simulate(MIPSprogram p, int pc, int maxSteps, int[] breakPoints, AbstractAction actor) throws ProcessingException {
         simulatorThread = new SimThread(p,pc,maxSteps,breakPoints,actor);
         simulatorThread.start();
      	
      	// Condition should only be true if run from command-line instead of GUI.
      	// If so, just stick around until execution thread is finished.
         if (actor == null) {
            Object dun = simulatorThread.get(); // this should emulate join()
            ProcessingException pe = simulatorThread.pe;
            boolean done = simulatorThread.done;
            if (done) SystemIO.resetFiles(); // close any files opened in MIPS progra
            this.simulatorThread = null;
            if (pe != null) {
               throw pe;
            }
            return done;
         }
         return true;
      }
   		
   
       /**
   	  *  Set the volatile stop boolean variable checked by the execution
   	  *  thread at the end of each MIPS instruction execution.  If variable
   	  *  is found to be true, the execution thread will depart
   	  *  gracefully so the main thread handling the GUI can take over.
   	  *  This is used by both STOP and PAUSE features.
   	  */     		
       public void stopExecution(AbstractAction actor) {
      
         if (simulatorThread != null) {
            simulatorThread.setStop(actor);
            for (StopListener l : stopListeners) {
               l.stopped(this);
            }
            simulatorThread = null;
         }
      }
   
      /* This interface is required by the Asker class in MassagesPane
       * to be notified about the fact that the user has requested to
       * stop the execution. When that happens, it must unblock the
       * simulator thread. */
       public interface StopListener {
          void stopped(Simulator s);
      }
   
      private ArrayList<StopListener> stopListeners = new ArrayList<StopListener>(1);
       public void addStopListener(StopListener l) {
         stopListeners.add(l);
      }
   
       public void removeStopListener(StopListener l) {
         stopListeners.remove(l);
      }
   
   	 // The Simthread object will call this method when it enters and returns from
   	 // its construct() method.  These signal start and stop, respectively, of
   	 // simulation execution.  The observer can then adjust its own state depending
   	 // on the execution state.  Note that "stop" and "done" are not the same thing.
   	 // "stop" just means it is leaving execution state; this could be triggered
   	 // by Stop button, by Pause button, by Step button, by runtime exception, by
   	 // instruction count limit, by breakpoint, or by end of simulation (truly done).
       private void notifyObserversOfExecutionStart(int maxSteps, int programCounter) {
         this.setChanged();
         this.notifyObservers(new SimulatorNotice(SimulatorNotice.SIMULATOR_START,
            maxSteps, RunSpeedPanel.getInstance().getRunSpeed(), programCounter) );
      }
   
       private void notifyObserversOfExecutionStop(int maxSteps, int programCounter) {
         this.setChanged();
         this.notifyObservers(new SimulatorNotice(SimulatorNotice.SIMULATOR_STOP,
            maxSteps, RunSpeedPanel.getInstance().getRunSpeed(), programCounter) );
      }
   	 
   	 
   	/**
   	 * SwingWorker subclass to perform the simulated execution in background thread.
   	 * It is "interrupted" when main thread sets the "stop" variable to true.
   	 * The variable is tested before the next MIPS instruction is simulated.  Thus
   	 * interruption occurs in a tightly controlled fashion.
   	 *
   	 * See SwingWorker.java for more details on its functionality and usage.  It is
   	 * provided by Sun Microsystems for download and is not part of the Swing library.
   	 */ 	
   		
       class SimThread extends SwingWorker {
         private MIPSprogram p;
         private int pc, maxSteps;
         private int[] breakPoints;
         private boolean done;
         private ProcessingException pe;
         private volatile boolean stop = false;
         private volatile AbstractAction stopper;
         private AbstractAction starter;
         private int constructReturnReason;

         private final CycleCounter cycleCounter = new CycleCounter();
      
         /**
      	 *  SimThread constructor.  Receives all the information it needs to simulate execution.
      	 *
      	 *  @param p  the MIPSprogram to be simulated
      	 *  @param pc address in text segment of first instruction to simulate
      	 *  @param maxSteps  maximum number of instruction steps to simulate.  Default of -1 means no maximum
      	 *  @param breakPoints  array of breakpoints (instruction addresses) specified by user
      	 *  @param starter the GUI component responsible for this call, usually GO or STEP.  null if none.
      	 */
          SimThread(MIPSprogram p, int pc, int maxSteps, int[] breakPoints, AbstractAction starter) {
            super(Globals.getGui()!=null);  
            this.p = p;
            this.pc = pc;
            this.maxSteps = maxSteps;
            this.breakPoints = breakPoints;
            this.done = false;
            this.pe = null;
            this.starter = starter;
            this.stopper = null;
         }
      	
      	/**
      	 * Sets to "true" the volatile boolean variable that is tested after each
      	 * MIPS instruction is executed.  After calling this method, the next test
      	 * will yield "true" and "construct" will return.
      	 *
      	 * @param actor the Swing component responsible for this call.  
      	 */
          public void setStop(AbstractAction actor) {
            stop = true;
            stopper = actor;
         }

          private boolean isInvalidCourseFetchAddress(int address, long upperAddress) {
            return (address % Instruction.INSTRUCTION_LENGTH) != 0 ||
               address < COURSE_TEXT_BASE || (long) address > upperAddress;
         }

          private boolean isInvalidP7FetchAddress(int address) {
            return isInvalidCourseFetchAddress(address, COURSE_TEXT_LIMIT);
         }

          private long getCourseUserTextLimit() {
            long haltDelaySlot = (long) courseHaltAddress.intValue() +
               Instruction.INSTRUCTION_LENGTH;
            return Math.min(haltDelaySlot, COURSE_TEXT_LIMIT);
         }

          private boolean isInvalidNonP7CourseFetchAddress(int address) {
            return courseHaltAddress != null &&
               isInvalidCourseFetchAddress(address, getCourseUserTextLimit());
         }

          private boolean hasLoadedP7CourseHandler() {
            return courseP7KernelTextEnd >= COURSE_P7_KERNEL_TEXT_BASE;
         }

          private boolean isUnloadedP7CourseFetchAddress(int address) {
            if (courseHaltAddress == null) {
               return false;
            }
            long loadedEnd = hasLoadedP7CourseHandler()
               ? (long) courseP7KernelTextEnd : getCourseUserTextLimit();
            return address < COURSE_TEXT_BASE || (long) address > loadedEnd;
         }

          private boolean isP7CoursePaddingAddress(int address) {
            return courseHaltAddress != null && hasLoadedP7CourseHandler() &&
               (long) address > getCourseUserTextLimit() &&
               address < COURSE_P7_KERNEL_TEXT_BASE;
         }

          private ProgramStatement getCourseStatement(int address)
            throws AddressErrorException {
            // The plugin emits zero words between the user halt delay slot and
            // the handler at 0x4180.  Execute those words as NOP even if MARS
            // happens to retain a sparse .ktext statement in that padding.
            if (Globals.getSettings().getExceptionForCourse() &&
               isP7CoursePaddingAddress(address)) {
               ProgramStatement paddingNop = new ProgramStatement(0, address);
               paddingNop.setBasicAssemblyStatement("nop");
               paddingNop.setMachineStatement(Binary.intToBinaryString(0));
               return paddingNop;
            }
            return Globals.memory.getStatement(address);
         }

          private boolean isUnloadedP7CourseHandler() {
            return courseHaltAddress != null &&
               isUnloadedP7CourseFetchAddress(Memory.exceptionHandlerAddress);
         }

          private Boolean terminateCourseInstructionAddressError(int address, int stoppedPC) {
            String expectedRanges = "the loaded user range 0x00003000 through " +
               Binary.intToHexString((int) getCourseUserTextLimit());
            if (Globals.getSettings().getExceptionForCourse()) {
               if (hasLoadedP7CourseHandler()) {
                  expectedRanges = "the loaded course image 0x00003000 through " +
                     Binary.intToHexString(courseP7KernelTextEnd);
               }
               else {
                  expectedRanges += " (no contiguous P7 handler was loaded at 0x00004180)";
               }
            }
            ErrorList errors = new ErrorList();
            errors.add(new ErrorMessage((MIPSprogram)null, 0, 0,
               "Course instruction address out of range: " + Binary.intToHexString(address) +
               "; expected " + expectedRanges + "."));
            this.pe = new ProcessingException(errors);
            this.constructReturnReason = EXCEPTION;
            this.done = true;
            SystemIO.resetFiles();
            Simulator.getInstance().notifyObserversOfExecutionStop(maxSteps, stoppedPC);
            return new Boolean(done);
         }

          private Boolean terminateCourseP7ContractViolation(
            ProcessingException violation, int stoppedPC) {
            this.pe = violation;
            this.constructReturnReason = EXCEPTION;
            this.done = true;
            SystemIO.resetFiles();
            Simulator.getInstance().notifyObserversOfExecutionStop(maxSteps, stoppedPC);
            return new Boolean(done);
         }

          private ProcessingException newCourseP7HwIntRiseViolation(
            ProgramStatement statement, int entryHwInt, int previousHwInt) {
            int newBits = Globals.HWInt & ~previousHwInt;
            return ProcessingException.courseContractViolation(statement,
               "new HWInt bit(s) " + Binary.intToHexString(newBits) +
               " rose while executing the loaded handler; entry HWInt was " +
               Binary.intToHexString(entryHwInt) + ".");
         }

          private ProgramStatement dispatchCourseFetchException() {
            Exceptions.setRegisters(Exceptions.ADDRESS_EXCEPTION_LOAD, true);
            ProgramStatement exceptionHandler = null;
            try {
               exceptionHandler = Globals.memory.getStatement(Memory.exceptionHandlerAddress);
            }
                catch (AddressErrorException aee) { }
            if (exceptionHandler != null) {
               RegisterFile.setProgramCounter(Memory.exceptionHandlerAddress);
            }
            return exceptionHandler;
         }
      	
      
      	/**
      	 *  This is comparable to the Runnable "run" method (it is called by
      	 *  SwingWorker's "run" method).  It simulates the program
      	 *  execution in the backgorund.
      	 *
      	 *  @return  boolean value true if execution done, false otherwise
      	 */
      	
          public Object construct() {
            // The next two statements are necessary for GUI to be consistently updated
         	// before the simulation gets underway.  Without them, this happens only intermittently,
         	// with a consequence that some simulations are interruptable using PAUSE/STOP and others
         	// are not (because one or the other or both is not yet enabled).
            Thread.currentThread().setPriority(Thread.NORM_PRIORITY-1);
            Thread.yield();  // let the main thread run a bit to finish updating the GUI
         	
            if (breakPoints == null || breakPoints.length == 0) {
               breakPoints = null;
            } 
            else {
               Arrays.sort(breakPoints);  // must be pre-sorted for binary search
            }
            
            Simulator.getInstance().notifyObserversOfExecutionStart(maxSteps, pc);
         	
            RegisterFile.initializeProgramCounter(pc);
            ProgramStatement statement = null;
            int initialPC = RegisterFile.getProgramCounter();
            if (!Globals.getSettings().getExceptionForCourse() &&
               isInvalidNonP7CourseFetchAddress(initialPC)) {
               return terminateCourseInstructionAddressError(initialPC, initialPC);
            }
            try {
               if (Globals.getSettings().getExceptionForCourse() &&
                  isInvalidP7FetchAddress(initialPC)) {
                  if (isUnloadedP7CourseHandler()) {
                     return terminateCourseInstructionAddressError(
                        Memory.exceptionHandlerAddress, initialPC);
                  }
                  statement = dispatchCourseFetchException();
                  if (statement == null) {
                     this.pe = new ProcessingException(Exceptions.ADDRESS_EXCEPTION_LOAD, true);
                     this.constructReturnReason = EXCEPTION;
                     this.done = true;
                     SystemIO.resetFiles();
                     Simulator.getInstance().notifyObserversOfExecutionStop(maxSteps, pc);
                     return new Boolean(done);
                  }
               }
               else if (Globals.getSettings().getExceptionForCourse() &&
                  isUnloadedP7CourseFetchAddress(initialPC)) {
                  return terminateCourseInstructionAddressError(initialPC, initialPC);
               }
               else {
                  statement = getCourseStatement(RegisterFile.getProgramCounter());
               }
            } 
                catch (AddressErrorException e) {
                  if (Globals.getSettings().getExceptionForCourse()) {
                     if (isUnloadedP7CourseHandler()) {
                        return terminateCourseInstructionAddressError(
                           Memory.exceptionHandlerAddress, initialPC);
                     }
                     statement = dispatchCourseFetchException();
                     if (statement != null) {
                        // Continue simulation at the course exception handler.
                     } else {
                        this.pe = new ProcessingException(Exceptions.ADDRESS_EXCEPTION_LOAD, true);
                        this.constructReturnReason = EXCEPTION;
                        this.done = true;
                        SystemIO.resetFiles();
                        Simulator.getInstance().notifyObserversOfExecutionStop(maxSteps, pc);
                        return new Boolean(done);
                     }
                  } else {
                  ErrorList el = new ErrorList();
                  el.add(new ErrorMessage((MIPSprogram)null,0,0,"invalid program counter value: "+Binary.intToHexString(RegisterFile.getProgramCounter())));
                  this.pe = new ProcessingException(el, e);
						// Next statement is a hack.  Previous statement sets EPC register to ProgramCounter-4
						// because it assumes the bad address comes from an operand so the ProgramCounter has already been
						// incremented.  In this case, bad address is the instruction fetch itself so Program Counter has
						// not yet been incremented.  We'll set the EPC directly here.  DPS 8-July-2013
                  Coprocessor0.updateRegister(Coprocessor0.EPC, RegisterFile.getProgramCounter());
                  this.constructReturnReason = EXCEPTION;
                  this.done = true;
                  SystemIO.resetFiles(); // close any files opened in MIPS program
                  Simulator.getInstance().notifyObserversOfExecutionStop(maxSteps, pc);
                  return new Boolean(done);
                  }
               }
            int steps = 0;
         	
         	// *******************  PS addition 26 July 2006  **********************
         	// A couple statements below were added for the purpose of assuring that when
         	// "back stepping" is enabled, every instruction will have at least one entry
         	// on the back-stepping stack.  Most instructions will because they write either
         	// to a register or memory.  But "nop" and branches not taken do not.  When the
         	// user is stepping backward through the program, the stack is popped and if
         	// an instruction has no entry it will be skipped over in the process.  This has
         	// no effect on the correctness of the mechanism but the visual jerkiness when
         	// instruction highlighting skips such instrutions is disruptive.  Current solution
         	// is to add a "do nothing" stack entry for instructions that do no write anything.
         	// To keep this invisible to the "simulate()" method writer, we
         	// will push such an entry onto the stack here if there is none for this instruction
         	// by the time it has completed simulating.  This is done by the IF statement
         	// just after the call to the simulate method itself.  The BackStepper method does
         	// the aforementioned check and decides whether to push or not.  The result
         	// is a a smoother interaction experience.  But it comes at the cost of slowing
         	// simulation speed for flat-out runs, for every MIPS instruction executed even
         	// though very few will require the "do nothing" stack entry.  For stepped or
         	// timed execution the slower execution speed is not noticeable.
         	//
         	// To avoid this cost I tried a different technique: back-fill with "do nothings"
         	// during the backstepping itself when this situation is recognized.  Problem
         	// was in recognizing all possible situations in which the stack contained such
         	// a "gap".  It became a morass of special cases and it seemed every weird test
         	// case revealed another one.  In addition, when a program
         	// begins with one or more such instructions ("nop" and branches not taken),
         	// the backstep button is not enabled until a "real" instruction is executed.
         	// This is noticeable in stepped mode.
         	// *********************************************************************
         	
            int pc = 0;  // added: 7/26/06 (explanation above)
            
            // P7: previous IRQ state for interrupt checking
            boolean prevIRQ = false;
            boolean courseP7HandlerActive = false;
            int courseP7HandlerEntryHwInt = 0;
            int courseP7HandlerPreviousHwInt = 0;

            while (statement != null) {
               pc = RegisterFile.getProgramCounter(); // added: 7/26/06 (explanation above)
               if (Simulator.getInstance().isCourseP7TestContractEnabled()) {
                  boolean inLoadedHandler =
                     Simulator.getInstance().isLoadedCourseP7HandlerAddress(pc);
                  if (inLoadedHandler && !courseP7HandlerActive) {
                     courseP7HandlerActive = true;
                     courseP7HandlerEntryHwInt = Globals.HWInt;
                     courseP7HandlerPreviousHwInt = Globals.HWInt;
                  }
                  else if (!inLoadedHandler) {
                     courseP7HandlerActive = false;
                     courseP7HandlerEntryHwInt = 0;
                     courseP7HandlerPreviousHwInt = 0;
                  }
               }
               else {
                  courseP7HandlerActive = false;
                  courseP7HandlerEntryHwInt = 0;
                  courseP7HandlerPreviousHwInt = 0;
               }
               // P7: enable timers at start of each instruction cycle
               if (Globals.getSettings().getExceptionForCourse()) {
                  TimerOne.setEnable(true);
                  TimerTwo.setEnable(true);
               }
               // P7: inject external interrupt from schedule (p7irq)
               if (Globals.getSettings().getExceptionForCourse()) {
                  int p7irqPC = RegisterFile.getProgramCounter();
                  if (Globals.getSettings().hasP7IrqAt(p7irqPC)) {
                     Globals.HWInt |= 4; // External interrupt bit 2
                     Globals.getSettings().markP7IrqFired(p7irqPC);
                  }
               }
               RegisterFile.incrementPC();
               boolean courseHaltCompletedThisInstruction = false;
               boolean instructionExecutionPhase = false;
            	// Perform the MIPS instruction in synchronized block.  If external threads agree
            	// to access MIPS memory and registers only through synchronized blocks on same
            	// lock variable, then full (albeit heavy-handed) protection of MIPS memory and
            	// registers is assured.  Not as critical for reading from those resources.
               synchronized (Globals.memoryAndRegistersLock) {
                  try {
                     if (courseP7HandlerActive &&
                        (Globals.HWInt & ~courseP7HandlerPreviousHwInt) != 0) {
                        throw newCourseP7HwIntRiseViolation(
                           statement, courseP7HandlerEntryHwInt,
                           courseP7HandlerPreviousHwInt);
                     }
                     if (courseP7HandlerActive) {
                        courseP7HandlerPreviousHwInt = Globals.HWInt;
                     }
                     if (Simulator.externalInterruptingDevice != NO_DEVICE) {
                        int deviceInterruptCode = externalInterruptingDevice;
                        Simulator.externalInterruptingDevice = NO_DEVICE;
                        throw new ProcessingException(statement, "External Interrupt", deviceInterruptCode);
                     }
                     // P7: check for hardware interrupts before instruction execution.
                     // Uses two-cycle deferral: prevIRQ captures isIter() from the PREVIOUS cycle.
                     // Must re-check isIter() this cycle because the instruction at p7irq PC
                     // may have changed IE/IM via mtc0, which should suppress the interrupt.
                     if (Globals.getSettings().getExceptionForCourse()) {
                        boolean takeInterrupt = prevIRQ;
                        Coprocessor0.updateCause();
                        boolean irqNow = Coprocessor0.isIter();
                        prevIRQ = irqNow;
                        if (takeInterrupt && irqNow) {
                           throw new ProcessingException(0); // Int exception, ExcCode=0
                        }
                     }
                     instructionExecutionPhase = true;
                     BasicInstruction instruction = (BasicInstruction)statement.getInstruction();
                     if (instruction == null) {
                        throw new ProcessingException(statement,
                            "undefined instruction ("+Binary.intToHexString(statement.getBinaryStatement())+")",
                            Exceptions.RESERVED_INSTRUCTION_EXCEPTION);
                     }
                     // THIS IS WHERE THE INSTRUCTION EXECUTION IS ACTUALLY SIMULATED!
                     Globals.displayRFchanging.clear();
                     Globals.displayDMchanging.clear();
                     instruction.getSimulationCode().simulate(statement);
                     if (courseP7HandlerActive) {
                        if ((Globals.HWInt & ~courseP7HandlerPreviousHwInt) != 0) {
                           throw newCourseP7HwIntRiseViolation(
                              statement, courseP7HandlerEntryHwInt,
                              courseP7HandlerPreviousHwInt);
                        }
                        // Capture acknowledgements/clears before Timer.update(), so a
                        // same-bit 0->1 transition caused by that update is still new.
                        courseP7HandlerPreviousHwInt = Globals.HWInt;
                     }
                     if (courseHaltAddress != null) {
                        int haltAddress = courseHaltAddress.intValue();
                        if (!Globals.getSettings().getDelayedBranchingEnabled()) {
                           courseHaltCompletedThisInstruction = pc == haltAddress;
                        }
                        else if (courseHaltDelaySlotPending) {
                           courseHaltCompletedThisInstruction =
                              pc == haltAddress + Instruction.INSTRUCTION_LENGTH &&
                              DelayedBranch.isTriggered() && statement.getBinaryStatement() == 0;
                           courseHaltDelaySlotPending = false;
                        }
                        else if (pc == haltAddress) {
                           courseHaltDelaySlotPending = true;
                        }
                     }
                     // P7: update timers after instruction execution
                     if (Globals.getSettings().getExceptionForCourse()) {
                        TimerOne.update();
                        TimerTwo.update();
                        // Handle delayed branch try-state
                        if (DelayedBranch.isTrydelay()) {
                           DelayedBranch.tryClean();
                        }
                        if (DelayedBranch.isTryjbranch()) {
                           DelayedBranch.tryChange();
                        }
                        if (courseP7HandlerActive &&
                           (Globals.HWInt & ~courseP7HandlerPreviousHwInt) != 0) {
                           throw newCourseP7HwIntRiseViolation(
                              statement, courseP7HandlerEntryHwInt,
                              courseP7HandlerPreviousHwInt);
                        }
                        if (courseP7HandlerActive) {
                           courseP7HandlerPreviousHwInt = Globals.HWInt;
                        }
                     }
                     cycleCounter.update(statement);  // added 3-Sept-2024, by swkfk to count cycles
                     if (Globals.getSettings().getOutputLoggingLevel() == 2) { // added 1-Nov-2022, by Toby to support BUAA CO.
                        SystemIO.printLog(String.format("@PC%08x -> %s (%08x)\n",
                           pc,
                           statement.getBasicAssemblyStatement(),
                           Integer.parseUnsignedInt(statement.getMachineStatement(), 2)
                        ));
                        for (String rf : Globals.displayRFchanging) {
                           SystemIO.printLog("\t\t" + rf + '\n');
                        }
                        for (String dm : Globals.displayDMchanging) {
                           SystemIO.printLog("\t\t" + dm + '\n');
                        }
                     } else if (Globals.getSettings().getOutputLoggingLevel() == 1) {
                        for (String rf : Globals.displayRFchanging) {
                           SystemIO.printLog(String.format("@%08x: %s\n",
                              pc,
                              rf
                           ));
                        }
                        for (String dm : Globals.displayDMchanging) {
                           SystemIO.printLog(String.format("@%08x: %s\n",
                              pc,
                              dm
                           ));
                        }
                     }
                  	
                  	// IF statement added 7/26/06 (explanation above)
                     if (Globals.getSettings().getBackSteppingEnabled()) {
                        Globals.program.getBackStepper().addDoNothing(pc);
                  }
               }
                      catch (ProcessingException pe) {
                         if (pe.isCourseContractViolation()) {
                            return terminateCourseP7ContractViolation(pe, pc);
                         }
                         if (courseP7HandlerActive) {
                            return terminateCourseP7ContractViolation(
                               ProcessingException.courseContractViolation(statement,
                                  "loaded-handler instruction at " +
                                  Binary.intToHexString(pc) +
                                  (instructionExecutionPhase
                                     ? " raised a synchronous exception."
                                     : " was interrupted before execution.")), pc);
                         }
                         // P7: update timers on exception too
                        if (Globals.getSettings().getExceptionForCourse()) {
                           TimerOne.update();
                           TimerTwo.update();
                        }
                        // P7: keep external interrupts pending until software acknowledges
                        // the interrupt generator by writing 0x7F20.  Cause.IP is refreshed
                        // from HWInt before each instruction, so clearing HWInt here would
                        // make the first handler mfc0 $13 observe IP=0 while the course
                        // testbench still holds interrupt high.
                        // NOTE: isEpcNotAligned() is currently always false (the 4-arg
                        // ProcessingException constructor is never invoked with ena=true).
                        // Kept as a safety guard for potential future EPC-alignment checks.
                        if (pe.isEpcNotAligned()) {
                           // P7: EPC not aligned - fatal error
                           this.constructReturnReason = EXCEPTION;
                           this.pe = pe;
                           this.done = true;
                           SystemIO.resetFiles();
                           Simulator.getInstance().notifyObserversOfExecutionStop(maxSteps, pc);
                           return new Boolean(done);
                        }
                        if (!pe.isCourseException() && pe.errors() == null) {
                           this.constructReturnReason = NORMAL_TERMINATION;
                           this.done = true;
                           SystemIO.resetFiles(); // close any files opened in MIPS program
                           Simulator.getInstance().notifyObserversOfExecutionStop(maxSteps, pc);
                           return new Boolean(done); // execution completed without error.
                        }
                        else {
                           // See if an exception handler is present.  Assume this is the case
                        	// if and only if memory location Memory.exceptionHandlerAddress
                        	// (e.g. 0x80000180) contains an instruction.  If so, then set the
                        	// program counter there and continue.  Otherwise terminate the
                        	// MIPS program with appropriate error message.
                           ProgramStatement exceptionHandler = null;
                           try {
                              exceptionHandler = Globals.memory.getStatement(Memory.exceptionHandlerAddress);
                           }
                               catch (AddressErrorException aee) { } // will not occur with this well-known addres
                           if (exceptionHandler != null) {
                              RegisterFile.setProgramCounter(Memory.exceptionHandlerAddress);
                           } 
                           else {
                              this.constructReturnReason = EXCEPTION;
                              this.pe = pe;
                              this.done = true;
                              SystemIO.resetFiles(); // close any files opened in MIPS program
                              Simulator.getInstance().notifyObserversOfExecutionStop(maxSteps, pc);
                              return new Boolean(done);
                           }
                        }
                     }
               }// end synchronized block

               if (courseHaltCompletedThisInstruction) {
                  lastTerminationReason = COURSE_HALT;
                  this.constructReturnReason = COURSE_HALT;
                  this.done = true;
                  SystemIO.resetFiles();
                  Simulator.getInstance().notifyObserversOfExecutionStop(maxSteps, pc);
                  return new Boolean(done);
               }
            	
            	///////// DPS 15 June 2007.  Handle delayed branching if it occurs./////
               if (DelayedBranch.isTriggered()) {
                  RegisterFile.setProgramCounter(DelayedBranch.getBranchTargetAddress());
                  DelayedBranch.clear();
               } 
               else if (DelayedBranch.isRegistered()) {
                  DelayedBranch.trigger();
               }//////////////////////////////////////////////////////////////////////
            	
            	// Volatile variable initialized false but can be set true by the main thread.
            	// Used to stop or pause a running MIPS program.  See stopSimulation() above.
               if (stop == true) { 
                  this.constructReturnReason = PAUSE_OR_STOP;
                  this.done = false;
                  Simulator.getInstance().notifyObserversOfExecutionStop(maxSteps, pc);
                  return new Boolean(done);
               }
            	//	Return if we've reached a breakpoint.					
               if((breakPoints != null) && 
               (Arrays.binarySearch(breakPoints,RegisterFile.getProgramCounter()) >= 0)) {
                  this.constructReturnReason = BREAKPOINT;
                  this.done = false;
                  Simulator.getInstance().notifyObserversOfExecutionStop(maxSteps, pc);
                  return new Boolean(done); // false;
               }
            	// Check number of MIPS instructions executed.  Return if at limit (-1 is no limit).
               if (maxSteps > 0) {
                  steps++;
                  if (steps >= maxSteps) {
                     this.constructReturnReason = MAX_STEPS;
                     this.done = false;
                     Simulator.getInstance().notifyObserversOfExecutionStop(maxSteps, pc);
                     return new Boolean(done);// false;
                  }
               }
            	
            	// schedule GUI update only if: there is in fact a GUI! AND
            	//                              using Run,  not Step (maxSteps > 1) AND
            	//                              running slowly enough for GUI to keep up
               //if (Globals.getGui() != null && maxSteps != 1 &&             
               if (interactiveGUIUpdater != null && maxSteps != 1 && 
                          RunSpeedPanel.getInstance().getRunSpeed() < RunSpeedPanel.UNLIMITED_SPEED) {
                  SwingUtilities.invokeLater(interactiveGUIUpdater);
               }
               if (Globals.getGui() != null || Globals.runSpeedPanelExists) { // OR added by DPS 24 July 2008 to enable speed control by stand-alone tool
                  if (maxSteps != 1 && 
                          RunSpeedPanel.getInstance().getRunSpeed() < RunSpeedPanel.UNLIMITED_SPEED) {
                     try { Thread.sleep((int)(1000/RunSpeedPanel.getInstance().getRunSpeed())); // make sure it's never zero!
                     } 
                         catch (InterruptedException e) {}
                  }
               }
               
            
               // Get next instruction in preparation for next iteration.

               // Course runs validate the PC before every fetch.  P7 dispatches
               // AdEL to its handler; earlier projects fail immediately instead
               // of accepting statements MARS happens to hold beyond the
               // submitted text image.
               int nextPC = RegisterFile.getProgramCounter();
               if (!Globals.getSettings().getExceptionForCourse() &&
                  isInvalidNonP7CourseFetchAddress(nextPC)) {
                  return terminateCourseInstructionAddressError(nextPC, pc);
               }
               if (Globals.getSettings().getExceptionForCourse()) {
                  if (isInvalidP7FetchAddress(nextPC)) {
                     if (isUnloadedP7CourseHandler()) {
                        return terminateCourseInstructionAddressError(
                           Memory.exceptionHandlerAddress, pc);
                     }
                     // Handle fetch exception inline
                     ProgramStatement exceptionHandler = dispatchCourseFetchException();
                     if (exceptionHandler != null) {
                        statement = exceptionHandler;
                        continue;
                     } else {
                        this.constructReturnReason = EXCEPTION;
                        this.pe = new ProcessingException(Exceptions.ADDRESS_EXCEPTION_LOAD, true);
                        this.done = true;
                        SystemIO.resetFiles();
                        Simulator.getInstance().notifyObserversOfExecutionStop(maxSteps, pc);
                        return new Boolean(done);
                   }
                }
                  else if (isUnloadedP7CourseFetchAddress(nextPC)) {
                     return terminateCourseInstructionAddressError(nextPC, pc);
                  }
                }

               try {
                  statement = getCourseStatement(RegisterFile.getProgramCounter());
               }
                  catch (AddressErrorException e) {
                     if (Globals.getSettings().getExceptionForCourse()) {
                        if (isUnloadedP7CourseHandler()) {
                           return terminateCourseInstructionAddressError(
                              Memory.exceptionHandlerAddress, pc);
                        }
                        // P7: fetch exception - use ADDRESS_EXCEPTION_LOAD
                        ProgramStatement exceptionHandler = dispatchCourseFetchException();
                        if (exceptionHandler != null) {
                           statement = exceptionHandler;
                           continue;
                        } else {
                           this.constructReturnReason = EXCEPTION;
                           this.pe = new ProcessingException(Exceptions.ADDRESS_EXCEPTION_LOAD, true);
                           this.done = true;
                           SystemIO.resetFiles();
                           Simulator.getInstance().notifyObserversOfExecutionStop(maxSteps, pc);
                           return new Boolean(done);
                        }
                     }
                     ErrorList el = new ErrorList();
                     el.add(new ErrorMessage((MIPSprogram)null,0,0,"invalid program counter value: "+Binary.intToHexString(RegisterFile.getProgramCounter())));
                     this.pe = new ProcessingException(el,e);
						   // Next statement is a hack.  Previous statement sets EPC register to ProgramCounter-4
						   // because it assumes the bad address comes from an operand so the ProgramCounter has already been
						   // incremented.  In this case, bad address is the instruction fetch itself so Program Counter has
						   // not yet been incremented.  We'll set the EPC directly here.  DPS 8-July-2013
                     Coprocessor0.updateRegister(Coprocessor0.EPC, RegisterFile.getProgramCounter());
                     this.constructReturnReason = EXCEPTION;
                     this.done = true;
                     SystemIO.resetFiles(); // close any files opened in MIPS program
                     Simulator.getInstance().notifyObserversOfExecutionStop(maxSteps, pc);
                     return  new Boolean(done);
                  }
            }
            // DPS July 2007.  This "if" statement is needed for correct program
         	// termination if delayed branching on and last statement in
         	// program is a branch/jump.  Program will terminate rather than branch,
         	// because that's what MARS does when execution drops off the bottom.
            if (DelayedBranch.isTriggered() || DelayedBranch.isRegistered()) {
               DelayedBranch.clear();
            }
         	// If we got here it was due to null statement, which means program
         	// counter "fell off the end" of the program.  NOTE: Assumes the 
         	// "while" loop contains no "break;" statements.
            this.constructReturnReason = CLIFF_TERMINATION;
            this.done = true;
            SystemIO.resetFiles(); // close any files opened in MIPS program
            Simulator.getInstance().notifyObserversOfExecutionStop(maxSteps, pc);
            return new Boolean(done); // true;  // execution completed
         }
         
      	
      	/**
      	 *   This method is invoked by the SwingWorker when the "construct" method returns.  
      	 *   It will update the GUI appropriately.  According to Sun's documentation, it 
      	 *   is run in the main thread so should work OK with Swing components (which are 
      	 *   not thread-safe).
      	 *
      	 *   Its action depends on what caused the return from construct() and what
      	 *   action led to the call of construct() in the first place.
      	 */
      	 
          public void finished() {
            // I want to display the cycle counts here
          if (Globals.getSettings().getCountCycles()) {
            Globals.displayOutput.println(cycleCounter.emitResult());
          }
           // If running from the command-line, then there is no GUI to update.
            if (Globals.getGui() == null) {
               return;
            }
            String starterName = (String) starter.getValue(AbstractAction.NAME);
            if (starterName.equals("Step")) {
               ((RunStepAction)starter).stepped(done,constructReturnReason,pe);
            }   
            if (starterName.equals("Go")) {
               if (done) {
                  ((RunGoAction)starter).stopped(pe,constructReturnReason);
               } 
               else if (constructReturnReason == BREAKPOINT) {
                  ((RunGoAction)starter).paused(done,constructReturnReason,pe);
               } 
               else {
                  String stopperName = (String) stopper.getValue(AbstractAction.NAME);
                  if ("Pause".equals(stopperName)) {
                     ((RunGoAction)starter).paused(done,constructReturnReason,pe);
                  }
                  else if ("Stop".equals(stopperName)) {
                     ((RunGoAction)starter).stopped(pe,constructReturnReason);
                  }
               }
            }
            return;
         }
         
      }
   	
       private class UpdateGUI implements Runnable {
          public void run() {
            if (Globals.getGui().getRegistersPane().getSelectedComponent() == 
                                                     Globals.getGui().getMainPane().getExecutePane().getRegistersWindow()) {
               Globals.getGui().getMainPane().getExecutePane().getRegistersWindow().updateRegisters();
            } 
            else {
               Globals.getGui().getMainPane().getExecutePane().getCoprocessor1Window().updateRegisters();
            }
            Globals.getGui().getMainPane().getExecutePane().getDataSegmentWindow().updateValues();
            Globals.getGui().getMainPane().getExecutePane().getTextSegmentWindow().setCodeHighlighting(true);
            Globals.getGui().getMainPane().getExecutePane().getTextSegmentWindow().highlightStepAtPC();   
         }
      }
   
   }



