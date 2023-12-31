package mars.tools;

import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Observable;

import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSeparator;
import javax.swing.JTable;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingConstants;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableColumn;
import javax.swing.table.TableColumnModel;

import mars.Globals;
import mars.ProgramStatement;
import mars.assembler.Symbol;
import mars.assembler.SymbolTable;
import mars.mips.hardware.AccessNotice;
import mars.mips.hardware.AddressErrorException;
import mars.mips.hardware.Memory;
import mars.mips.hardware.MemoryAccessNotice;
import mars.mips.hardware.Register;
import mars.mips.hardware.RegisterAccessNotice;
import mars.mips.hardware.RegisterFile;
import mars.mips.instructions.Instruction;
import mars.simulator.BackStepper;
import mars.simulator.Simulator;
import mars.simulator.SimulatorNotice;
import mars.util.Binary;
import mars.venus.FileStatus;
import mars.venus.VenusUI;

/*
 * Allows the user to view in real time the memory modification operations taking place in the stack segment with emphasis to
 * $sp-relative memory accesses. The user can also observe how pushes/pops to/from the stack take place.
 */

@SuppressWarnings({ "serial", "deprecation" })
public class StackVisualizer extends AbstractMarsToolAndApplication {
	private static String name        = "Stack Visualizer";
	private static String heading     = "Tracking Stack Memory";

	// We need the following definition here to initialize numberOfColumns
	/** Table column names for displaying data per word. */
	private final String[] colNames = {"Address", "Word-length Data", "Stored Reg", "Status"};

	/**
	 * True if {@link StackVisualizer} is currently running
	 * as a stand-alone program (MARS application)
	 */
	private static boolean inStandAloneMode = false;

	/*
	 * Memory.stackBaseAddress:  word-aligned
	 * Memory.stackLimitAddress: word-aligned
	 * Max stack address value:  Memory.stackBaseAddress + (WORD_LENGTH_BYTES-1)
	 * Min stack address value:  Memory.stackLimitAddress
	 *
	 * Stack grows towards lower addresses: .stackBaseAddress > .stackLimitAddress
	 * Word-length operations can take place in both .stackBaseAddress and .stackLimitAddress
	 */
	/** Register number of stack pointer (29) */
	private final int     SP_REG_NUMBER             = RegisterFile.STACK_POINTER_REGISTER;
	/** Register number of return address (31) */
	private final int     RA_REG_NUMBER             = RegisterFile.getNumber("$ra");
	/** Stack pointer's initial address/value */
	private final int     SP_INIT_ADDR              = Memory.stackPointer;
	private final Memory  memInstance               = Memory.getInstance();
	private final boolean endianness                = memInstance.getByteOrder();
	// private final boolean LITTLE_ENDIAN             = Memory.LITTLE_ENDIAN;       // for quick access
	/** MIPS word length in bytes. */
	private final int     WORD_LENGTH_BYTES         = Memory.WORD_LENGTH_BYTES;   // for quick access
	/** MIPS word length in bits. */
	private final int     WORD_LENGTH_BITS          = WORD_LENGTH_BYTES << 3;
	/** I-format RS (source register) index in operand list. */
	private final int     I_RS_OPERAND_LIST_INDEX   = 0;
	/** J-format Address index in operand list. */
	private final int     J_ADDR_OPERAND_LIST_INDEX = 0;
	/** R-format RS (source register) index in operand list. */
	private final int     R_RS_OPERAND_LIST_INDEX   = 0;
	/** Maximum value stack pointer can currently take (word-aligned). */
	private int           maxSpValueWordAligned     = SP_INIT_ADDR;
	/** Register name to be stored in stack segment. */
	private String        regNameToBeStoredInStack  = null;
	/** Name of the (subroutine) frame to be allocated in stack segment. */
	private String        frameNameToBeCreated      = null;
	/** Whether $ra was written/updated in the last instruction. */
	private boolean       raWrittenInPrevInstr      = false;
	/**
	 * Return Address Stack. Target addresses of jal instructions are pushed and
	 * then are popped and matched when jr instructions are encountered.
	 */
	private final ArrayList<Integer> ras            = new ArrayList<Integer>();
	/**
	 * Active subroutine statistics. Stores how many times each subroutine is active
	 * (called but is yet to complete execution).
	 */
	private final ActiveSubroutineStats activeFunctionCallStats = new ActiveSubroutineStats();
	/** Current stack base address. Used to detect memory configuration changes */
	private int currStackBaseAddress = Memory.stackBaseAddress;

	// GUI-Related fields
	private final int     windowWidth               = 600;
	private final int     windowHeight              = 600;
	/** Table column index where memory address should be stored. Should always be first column. */
	private final int     ADDRESS_COLUMN            = 0;
	/** Table column index where the first byte of memory data should be stored. Should always be second column. */
	private final int     FIRST_BYTE_COLUMN         = 1;
	/** Table column index where the last byte of memory data should be stored. */
	private final int     LAST_BYTE_COLUMN          = FIRST_BYTE_COLUMN + (WORD_LENGTH_BYTES - 1);
	/** Table column index where the word-length memory data should be stored. Should always be second column. */
	private final int     WORD_COLUMN               = 1;
	/** How many rows the table should initially have. */
	private final int     INITIAL_ROW_COUNT         = 36;
	/** Current number of table columns. */
	private int           numberOfColumns           = colNames.length;
	/** Current number of table rows. (-1) before table initialization. */
	private int           numberOfRows              = 0;
	/** Offset of frame name ("Call Layout") column from table end. */
	private final int     frameNameColOffsetFromEnd = 0;
	/** Offset of stored register column from table end. */
	private final int     storedRegColOffsetFromEnd = 1;
	/** Table column index where frame name should be stored. */
	private int           frameNameColumn           = calcTableColIndex(frameNameColOffsetFromEnd);
	/** Table column index where stored register name should be stored. */
	private int           storedRegisterColumn      = calcTableColIndex(storedRegColOffsetFromEnd);
	/** Threshold to decide whether more table rows should be added. */
	private final int     REMAINING_ROWS_THRESHOLD  = 5;
	/** Table row where stack pointer points to. */
	private int           spDataRowIndex            = 0;
	/** Table column where stack pointer points to. */
	private int           spDataColumnIndex         = LAST_BYTE_COLUMN;
	private JTable        table;
	private JPanel        panel;
	private JScrollPane   scrollPane;
	private JCheckBox     dataPerByte;
	private JCheckBox     hexAddressesCheckBox;
	private JCheckBox     hexValuesCheckBox;
	private JCheckBox     jalEquivInstrCheckBox;
	private final int     LIGHT_YELLOW = 0xFFFF99;
	private final int     LIGHT_ORANGE = 0xFFC266;
	private final int     LIGHT_GRAY   = 0xE0E0E0;
	private final int     GRAY         = 0x999999;
	private final int     WHITE        = 0xFFFFFF;
	private boolean       disabledBackStep = false;
	private boolean       displayDataPerByte = false;
	private boolean       displayHexAddresses = true;
	private boolean       displayHexValues = true;
	private boolean       detectJalEquivalentInstructions = false;
	private final DefaultTableModel tableModel = new DefaultTableModel();
	
	/** Used for debugging purposes. */
	private final boolean debug = true, printMemContents = false, debugBackStepper = false;


	protected StackVisualizer(String title, String heading) {
		super(title, heading);
	}


	public StackVisualizer() {
		super(StackVisualizer.name + ", ", StackVisualizer.heading);
	}


	/**
	 * Main method provided for use as a MARS application (stand-alone program).
	 */
	public static void main(String[] args) {
		inStandAloneMode = true;
		new StackVisualizer(StackVisualizer.name + " stand-alone, ", StackVisualizer.heading).go();
	}


	@Override
	public String getName() {
		return name;
	}


	@Override
	protected JComponent buildMainDisplayArea() {
		GridBagConstraints c = new GridBagConstraints();
		c.fill = GridBagConstraints.BOTH;
		c.gridx = c.gridy = 0;
		c.weightx = c.weighty = 1.0;
		panel = new JPanel(new GridBagLayout());
		panel.setPreferredSize(new Dimension(windowWidth, windowHeight));
		for (String s : colNames)
			tableModel.addColumn(s);
		table = new JTable(tableModel);
		table.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
		table.getTableHeader().setReorderingAllowed(false);
		table.setEnabled(false);
		table.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
			@Override
			public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
				final Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
				c.setBackground(calcTableCellColor(row, column));

				if (column == WORD_COLUMN)
					setHorizontalAlignment(SwingConstants.RIGHT);
				else if (column == storedRegisterColumn || column == frameNameColumn)
					setHorizontalAlignment(SwingConstants.CENTER);
				else
					setHorizontalAlignment(SwingConstants.LEFT);
				return c;
			}
		});
		// table.setAutoResizeMode( JTable.AUTO_RESIZE_OFF );
		// resizeTableColumns();

		scrollPane = new JScrollPane(table);
		scrollPane.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS);
		scrollPane.setVisible(true);
		panel.add(scrollPane, c);
		table.setFillsViewportHeight(true);

		c.gridy++;	// change line
		c.weightx = 1.0;
		c.weighty = 0;
		dataPerByte = new JCheckBox("");
		dataPerByte.setSelected(true);
		// panel.add(dataPerByte, c);
		dataPerByte.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				displayDataPerByte = false;
				transformTableModel(colNames);
			}
		});

		c.gridy++;	// change line
		hexAddressesCheckBox = new JCheckBox("");
		hexAddressesCheckBox.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				// displayHexAddresses = hexAddressesCheckBox.isSelected();
				displayHexAddresses = true;
				getStackData();
				table.repaint();
			}
		});
		hexAddressesCheckBox.setSelected(true);
		// panel.add(hexAddressesCheckBox, c);

		c.gridy++;	// change line
		hexValuesCheckBox = new JCheckBox("");
		hexValuesCheckBox.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				// displayHexValues = hexValuesCheckBox.isSelected();
				displayHexValues = true;
				getStackData();
				table.repaint();
			}
		});
		hexValuesCheckBox.setSelected(true);
		// panel.add(hexValuesCheckBox, c);

		c.gridy++;
		panel.add(new JSeparator(), c);

		c.gridy++;
		jalEquivInstrCheckBox = new JCheckBox("");
		jalEquivInstrCheckBox.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				// detectJalEquivalentInstructions = jalEquivInstrCheckBox.isSelected();
			}
		});
		// jalEquivInstrCheckBox.setSelected(false);
		// panel.add(jalEquivInstrCheckBox, c);

		return panel;
	}


	/**
	 * Calculates what color table cell ({@code row},{@code column}) should be colored.
	 *
	 * @param row table cell row.
	 * @param column table cell column.
	 * @return the calculated color.
	 */
	private Color calcTableCellColor(int row, int column) {
		int color = WHITE;
		if (row == spDataRowIndex) {
			color = LIGHT_YELLOW;
		}
		else if (row > spDataRowIndex) {
			color = GRAY;
		}
		else if (row % 2 == 0) {
			color = LIGHT_GRAY;
		}
		return new Color(color);
	}


	/**
	 * Transform table model so that new columns match {@code columnNames[]}.
	 *
	 * @param columnNames the new table columns.
	 */
	private synchronized void transformTableModel(String columnNames[]) {
		Object storedRegColumnData[] = new Object[numberOfRows];
		Object frameNameColumnData[] = new Object[numberOfRows];

		// Backup storedRegister and frameName columns
		for (int row = 0; row < numberOfRows; row++) {
			storedRegColumnData[row] = tableModel.getValueAt(row, storedRegisterColumn);
			frameNameColumnData[row] = tableModel.getValueAt(row, frameNameColumn);
		}

		table.setVisible(false);   // Used to avoid rendering delays
		tableModel.setColumnCount(0);	// Clear table columns
		for (String s : columnNames)
			tableModel.addColumn(s);	// Add new table columns

		// Update table-related data
		numberOfColumns = tableModel.getColumnCount();
		numberOfRows = tableModel.getRowCount();
		frameNameColumn = calcTableColIndex(frameNameColOffsetFromEnd);
		storedRegisterColumn = calcTableColIndex(storedRegColOffsetFromEnd);
		// resizeTableColumns();

		// Restore toredRegister and frameName columns
		for (int row = 0; row < numberOfRows; row++) {
			tableModel.setValueAt(storedRegColumnData[row], row, storedRegisterColumn);
			tableModel.setValueAt(frameNameColumnData[row], row, frameNameColumn);
		}
		getStackData();
		table.repaint();
		table.setVisible(true);
	}


	@Override
	protected void initializePostGUI() {
		if (inStandAloneMode == false) {
			connectButton.addActionListener(new ActionListener() {
				@Override
				public void actionPerformed(ActionEvent e) {
					if (connectButton.isConnected()) {
						restoreBackStepper();
					} else {
						checkMemConfChanged();
						/*
						 * User program should be recompiled (and executed) after
						 * StackVisualizer is launched. This is required for
						 * coherently storing the subroutine call stack.
						 */
						runButtonsSetEnabled(false);
						/* Connecting StackVisualizer in the middle of program execution
						 * will disable Back Stepper but the button will show as enabled.
						 * Maybe we should disable it by hand or just don't mess with it.
						 */
						disableBackStepper();

						refreshGui();
					}
				}
			});
		}
		checkMemConfChanged();
		Simulator.getInstance().addObserver(this);
		addNewTableRows(INITIAL_ROW_COUNT);
		updateSpDataRowColIndex();
		table.repaint();
	}


	/**
	 * Equivalent to {@code getStackData(0, numberOfRows)}.
	 */
	private void getStackData() {
		getStackData(0, numberOfRows-1);
	}


	/**
	 * Equivalent to {@code getStackData(row, row)}.
	 */
	private void getStackData(int row) {
		getStackData(row, row);
	}


	/**
	 * Fills/updates table rows [{@code startRow}, {@code endRow}] with data directly
	 *  from Mars' memory instance.
	 *
	 * This method fires a {@link MemoryAccessNotice} every time it reads from memory.
	 * For this reason it should not be called in a code block handling a
	 * {@link MemoryAccessNotice} of {@code AccessNotice.READ} type as it will lead
	 * in infinite recursive calls of itself.
	 */
	private synchronized void getStackData(int startRow, int endRow) {
		int row, col, addr;

		if (startRow < 0 || endRow < 0 || endRow >= numberOfRows) {
			if (printMemContents)
				System.out.println("getStackData end (invalid arguments)\n");
			return;
		}

		if (printMemContents)
			System.out.println("getStackData(" + row + "," + endRow + ") start");
		
		addr = maxSpValueWordAligned - startRow * WORD_LENGTH_BYTES;
		
		/*
		 * Initial value of spAddr is 0x7FFFEFFC = 2147479548 (Default MIPS memory configuration).
		 * The first 4 bytes (1 word) to be displayed are:
		 * 0x7FFFEFFF, 0x7FFFEFFE, 0x7FFFEFFD, 0x7FFFEFFC or in decimal value:
		 * 2147479551, 2147479550, 2147479549, 2147479548.
		 */
		for (row = startRow; row <= endRow; row++, addr -= WORD_LENGTH_BYTES) {
			tableModel.setValueAt(formatAddress(addr), row, ADDRESS_COLUMN);
			try {
				tableModel.setValueAt(formatNByteLengthMemContents(WORD_LENGTH_BYTES, memInstance.getWord(addr)),
							row, WORD_COLUMN);
				if (printMemContents) {
					System.out.print(tableModel.getValueAt(row, 0) + ": ");
					if (displayDataPerByte) {
						for (int i = FIRST_BYTE_COLUMN; i <= LAST_BYTE_COLUMN; i++)
							System.out.print(tableModel.getValueAt(row, i) + (i == LAST_BYTE_COLUMN ? "" : ","));
					} else {
						System.out.print(tableModel.getValueAt(row, WORD_COLUMN));
					}
					System.out.print(" (" + tableModel.getValueAt(row, storedRegisterColumn) + ")");
					System.out.println(" [" + tableModel.getValueAt(row, frameNameColumn) + "]");
				}
			} catch (AddressErrorException aee) {
				System.err.println("getStackData(): " + formatAddress(aee.getAddress()) + " AddressErrorException");
			} catch (Exception e) {
				e.printStackTrace();
			}
		}

		if (printMemContents)
			System.out.println("getStackData end\n");
	}


	@Override
	protected void addAsObserver() {
		// To observe stack segment, actual parameters should be
		// reversed due to higher to lower address stack growth.
		addAsObserver(Memory.stackLimitAddress, Memory.stackBaseAddress);
		addAsObserver(RegisterFile.getRegisters()[SP_REG_NUMBER]);
		addAsObserver(Memory.textBaseAddress, Memory.textLimitAddress);
		addAsObserver(RegisterFile.getRegisters()[RA_REG_NUMBER]);
	}


	@Override
	protected void deleteAsObserver() {
		super.deleteAsObserver(); // Stop observing memory (default)
		deleteAsObserver(RegisterFile.getRegisters()[SP_REG_NUMBER]); // Stop observing $sp
		deleteAsObserver(RegisterFile.getRegisters()[RA_REG_NUMBER]); // Stop observing $ra
	}


	@Override
	protected void processMIPSUpdate(Observable resource, AccessNotice notice) {
//		System.out.println(notice.accessIsFromMIPS() +" " + notice.accessIsFromGUI() + " " + notice);

		if (!notice.accessIsFromMIPS())
			return;

		if (notice instanceof MemoryAccessNotice) {
			MemoryAccessNotice m = (MemoryAccessNotice) notice;
			if (Memory.inTextSegment(m.getAddress()))
				processTextMemoryUpdate(m);
			else
				processStackMemoryUpdate(m);
		}
		else if (notice instanceof RegisterAccessNotice) {
			RegisterAccessNotice r = (RegisterAccessNotice) notice;
			processRegisterAccessNotice(r);
		}
	}


	/**
	 * Processes a received register update/notice (Read or Write).
	 */
	private void processRegisterAccessNotice(RegisterAccessNotice notice) {
		// Currently only $sp is observed ($ra also but not for stack modification ops)
		if (notice.getAccessType() == AccessNotice.READ)
			return;

		if (debug)
			System.out.println("\nRegisterAccessNotice (W): " + notice.getRegisterName() + " value: " + getSpValue());

		if (notice.getRegisterName().equals("$sp")) {
			int oldSpDataRowIndex = spDataRowIndex;
			updateSpDataRowColIndex();
			resetStoredRegAndFrameNameColumns(spDataRowIndex + 1, oldSpDataRowIndex);
//			 System.out.println("SP value: " + formatAddress(getSpValue()) + " - tableIndex: " + spDataRowIndex);
			// Add more rows if we are reaching current row count
			if (spDataRowIndex + REMAINING_ROWS_THRESHOLD > numberOfRows) {
				addNewTableRows(5);
			}
			table.repaint(); // Required for coloring $sp position during popping.
		} else if (notice.getRegisterName().equals("$ra")) {
			raWrittenInPrevInstr = true;
		}
	}


	/**
	 * Processes a received memory update/notice (Read or Write) targeting the stack segment.
	 */
	private void processStackMemoryUpdate(MemoryAccessNotice notice) {
		String regName = "", frameName = "";

		if (notice.getAccessType() == AccessNotice.READ)
			return;

		if (regNameToBeStoredInStack != null) {
			regName = regNameToBeStoredInStack;
			regNameToBeStoredInStack = null;
		}

		if (frameNameToBeCreated != null) {
			frameName = frameNameToBeCreated;
			frameNameToBeCreated = null;
		}

		if (debug) {
			System.out.println("\nStackAccessNotice (" +
					((notice.getAccessType() == AccessNotice.READ) ? "R" : "W") + "): "
					+ notice.getAddress() + " value: " + notice.getValue() +
					" (stored: " + regName + ")");
		}

		int row;
		try {
			row = getTableRowIndex(notice.getAddress());
		} catch (SVException sve) {
			System.err.println("processStackMemoryUpdate(): " + sve.getMessage());
			return;
		}

		if (debug)
			System.out.println("Addr: " + formatAddress(notice.getAddress()) + " - tableIndex: " + row + " (" + regName + ")");

		tableModel.setValueAt(regName, row, storedRegisterColumn);
		tableModel.setValueAt(frameName, row, frameNameColumn);
		getStackData(row);
		table.repaint();
	}


	/**
	 * Adds more rows in table.
	 *
	 * @param numRowsToAdd the number of rows to add.
	 */
	private synchronized void addNewTableRows(int numRowsToAdd) {
		int remainingRowsToAdd = maxTableRowsAllowed() - numberOfRows;
		if (numRowsToAdd > remainingRowsToAdd)
			numRowsToAdd = remainingRowsToAdd;
		if (numRowsToAdd == 0) {
			return;
		}
		for (int ri = 0; ri < numRowsToAdd; ri++)
			tableModel.addRow(new Object[numberOfColumns]);
		numberOfRows = tableModel.getRowCount();
		getStackData();
	}


	/**
	 * @return the maximum allowed number of table rows.
	 */
	private int maxTableRowsAllowed() {
		return (maxSpValueWordAligned - Memory.stackLimitAddress) / WORD_LENGTH_BYTES;
	}


	/**
	 * @return the index of the table row that {@code memAddress} should be stored
	 * if it belongs to the stack segment; else (-1).
	 * @throws SVException
	 */
	private int getTableRowIndex(int memAddress) throws SVException {
		if (!isStackSegAddress(memAddress)) {
			throw new SVException("An address not in the stack segment was provided (" + formatAddress(memAddress) + ")");
		}
		int rowIndex = (maxSpValueWordAligned - alignToCurrentWordBoundary(memAddress)) / WORD_LENGTH_BYTES;
		if (rowIndex >= numberOfRows) {
			addNewTableRows(rowIndex - numberOfRows + 10);
			table.repaint();
		}
		if (rowIndex < 0) { // Higher address than $sp value at program start
			int numNewRows = -rowIndex;
			int newMaxSpValueWordAligned = maxSpValueWordAligned + numNewRows * WORD_LENGTH_BYTES;
			if (newMaxSpValueWordAligned > Memory.stackBaseAddress) {
				numNewRows -= (newMaxSpValueWordAligned - Memory.stackBaseAddress) / WORD_LENGTH_BYTES;
			}
			maxSpValueWordAligned += numNewRows * WORD_LENGTH_BYTES;
			addNewTableRows(numNewRows);
			refreshGui();
			return (maxSpValueWordAligned - alignToCurrentWordBoundary(memAddress)) / WORD_LENGTH_BYTES;
		}
		return rowIndex;
	}


	/**
	 * @return the index of the table column that {@code memAddress} should be stored
	 * if it belongs to the stack segment; else (-1).
	 * @throws SVException
	 */
	private int getTableColumnIndex(int memAddress) throws SVException {
		if (!isStackSegAddress(memAddress))
			throw new SVException("An address not in the stack segment was provided (" + formatAddress(memAddress) + ")");
		return LAST_BYTE_COLUMN - (memAddress % WORD_LENGTH_BYTES);
	}


	/**
	 * @return true if {@code memAddress} is in stack segment; else false.
	 */
	private boolean isStackSegAddress(int memAddress) {
		/*
		 * In default memory configuration .stackLimitAddress is address 0x7fbffffc instead of 0x10040000
		 * mentioned in "MIPS Memory Configuration" window.
		 */
		return (memAddress > Memory.stackLimitAddress && memAddress <= Memory.stackBaseAddress);
	}


	/**
	 * Processes a received memory update/notice (Read or Write).
	 */
	private void processTextMemoryUpdate(MemoryAccessNotice notice) {
		if (notice.getAccessType() == AccessNotice.WRITE)
			return;

		if (debug) {
			System.out.println("\nTextAccessNotice (R): " + notice.getAddress()
					+ " value: " + notice.getValue() /*+ " = "*/);
		}
//		printBin(notice.getValue());

		boolean localRaWrittenInPrevInstr = raWrittenInPrevInstr;
		raWrittenInPrevInstr = false;

		try {
			ProgramStatement stmnt =  memInstance.getStatementNoNotify(notice.getAddress());

			/*
			 * The check below is required in case user program is finished running
			 * by dropping of the bottom. This happens when an execution termination
			 * service (Code 10 in $v0) does NOT take place.
			 */
			if (stmnt == null)
				return;

			Instruction instr = stmnt.getInstruction();
			String instrName = instr.getName();
			int[] operands;

			if (isStoreInstruction(instrName)) {
				if (debug)
					System.out.println("Statement TBE: " + stmnt.getPrintableBasicAssemblyStatement());
				operands = stmnt.getOperands();
//				for (int i = 0; i < operands.length; i++)
//					System.out.print(operands[i] + " ");
//				System.out.println();
				regNameToBeStoredInStack = RegisterFile.getRegisters()[operands[I_RS_OPERAND_LIST_INDEX]].getName();
			}
			else if (isJumpInstruction(instrName) || isJumpAndLinkInstruction(instrName)) {
				int targetAdrress = stmnt.getOperand(J_ADDR_OPERAND_LIST_INDEX) * WORD_LENGTH_BYTES;
				String targetLabel = addrToTextSymbol(targetAdrress);
				if (targetLabel != null) {
					if (debug) {
						System.out.print("Jumping to: " + targetLabel);
						if (isJumpAndLinkInstruction(instrName))
							System.out.println(" (" + (ras.size()) + ")");
						else
							System.out.println("");
					}
				}
			}
		} catch (AddressErrorException aee) {
			System.err.println("processTextMemoryUpdate(): " + formatAddress(aee.getAddress()) + " AddressErrorException");
		} catch (Exception e) {
			e.printStackTrace();
		}
	}


	/**
	 * Update {@code ras}, {@code activeFunctionCallStats} and {@code frameNameToBeCreated}
	 * as of a new subroutine call.
	 * @param stmnt the jump/jal instruction statement that invokes the new subroutine call.
	 * @param targetLabel the name/label of the new subroutine that is called.
	 */
	private void registerNewSubroutineCall(ProgramStatement stmnt, String targetLabel) {
		ras.add(stmnt.getAddress());
		Integer count = activeFunctionCallStats.addCall(targetLabel);
		frameNameToBeCreated = targetLabel + " (" + count + ")";
	}


	/**
	 * @param instrName instruction name.
	 *
	 * @return true if instruction name matches "sw", "sh", "sc" or "sb"; else false.
	 */
	private boolean isStoreInstruction(String instrName) {
		if (instrName.equals("sw") || instrName.equals("sh") ||
				instrName.equals("sc") || instrName.equals("sb"))
			return true;
		return false;
	}


	/**
	 * @param instrName instruction name.
	 *
	 * @return true if instruction name matches "j"; else false.
	 */
	private boolean isJumpInstruction(String instrName) {
		return (instrName.equals("j"));
	}


	/**
	 * @param instrName instruction name.
	 *
	 * @return true if instruction name matches "jal"; else false.
	 */
	private boolean isJumpAndLinkInstruction(String instrName) {
		return (instrName.equals("jal"));
	}


	/**
	 * @param instrName instruction name.
	 *
	 * @return true if instruction name matches "jr"; else false.
	 */
	private boolean isJumpRegInstruction(String instrName) {
		return (instrName.equals("jr"));
	}


	/**
	 * Translates a text segment address ({@code memAddress}) to a symbol/label.
	 *
	 * @return the corresponding label; else null.
	 */
	private String addrToTextSymbol(int memAddress) {
		String addrStr = String.valueOf(memAddress);
		SymbolTable localSymTable = Globals.program.getLocalSymbolTable();
		Symbol symbol = localSymTable.getSymbolGivenAddressLocalOrGlobal(addrStr);
		if (symbol != null) {
			// System.out.println("Symbol: " + symbol.getName());
			return symbol.getName();
		}
		System.err.println("addrToTextSymbol(): Error translating address to label");
		return null;
	}


	/**
	 * @return the current stack pointer ($sp) value.
	 */
	private int getSpValue() {
		return RegisterFile.getValue(SP_REG_NUMBER);
	}


	/**
	 * Disables back stepping.
	 */
	private void disableBackStepper() {
		if (Globals.program == null)
			return;
		BackStepper bs = Globals.program.getBackStepper();
		if (bs == null)
			return;
		if (bs.enabled()) {
			if (debugBackStepper)
				System.out.println("Disabled BackStepper");
			bs.setEnabled(false);
			disabledBackStep = true;
		}
	}


	/**
	 * Re-enables back stepping.
	 */
	private void restoreBackStepper() {
		if (disabledBackStep) {
			disabledBackStep = false;
			if (Globals.program == null)
				return;
			BackStepper bs = Globals.program.getBackStepper();
			if (bs == null)
				return;
			if (!bs.enabled()) {
				if (debugBackStepper)
					System.out.println("Enabled BackStepper");
				bs.setEnabled(true);
			}
		}
	}


	/**
	 * Enables or disables Run buttons as of parameter.
	 */
	private void runButtonsSetEnabled(boolean enable) {
		if (enable == true)
			FileStatus.set(FileStatus.RUNNABLE);
		else
			FileStatus.set(FileStatus.TERMINATED);
	}


	/**
	 * Aligns the given address to the corresponding full-word boundary, if not already aligned.
	 *
	 * @param memAddress the memory address to be aligned (any {@code int} value is potentially valid).
	 * @return the calculated word-aligned address (divisible by {@code WORD_LENGTH_BYTES}).
	 */
	private int alignToCurrentWordBoundary(int memAddress) {
		if (Memory.wordAligned(memAddress))
			return memAddress;
		return (Memory.alignToWordBoundary(memAddress) - WORD_LENGTH_BYTES);
	}


	/**
	 * Formats a memory address to hexadecimal or decimal representation according to {@code hexAddressesCheckBox}.
	 *
	 * @param memAddress the memory address to be formatted.
	 * @return a string containing the hexadecimal or decimal representation of {@code memAddress}.
	 */
	private String formatAddress(int memAddress) {
		if (displayHexAddresses)
			return Binary.intToHexString(memAddress);
		else
			return Integer.toString(memAddress);
	}


	/**
	 * Formats memory contents of N-byte length to hexadecimal or decimal representation
	 * according to hexAddressesCheckBox. In case of hexadecimal representation, no
	 * "0x" prefix is added.
	 *
	 * @param numBytes memory content length in bytes.
	 * @param data memory content to be formatted.
	 * @return a string containing the hexadecimal or decimal representation of data.
	 */
	private String formatNByteLengthMemContents(int numBytes, int data) {
		if (displayHexValues)
			return nBytesToHexStringNoPrefix(numBytes, data);
		else
			return Integer.toString(data);
	}


	/**
	 * Formats data of N-byte length to a hexadecimal representation string without a "0x" prefix.
	 *
	 * @param numBytes data length in bytes.
	 * @param data the data to be formatted.
	 * @return a string containing the resulted 2*N hexadecimal digits.
	 * Leading zeros are added if required.
	 */
	private String nBytesToHexStringNoPrefix(int numBytes, int data) {
		String leadingZero = new String("0");
		String ret = Integer.toHexString(data);
		while (ret.length() < (numBytes<<1)) // Add leading zeros if required
			ret = leadingZero.concat(ret);
		return ret;
	}


	/**
	 * Print the binary representation of a number without a "0b" prefix.
	 *
	 * @param num the number to be printed.
	 */
	@SuppressWarnings("unused")
	private void printBin(int num) {
		int count = 0;
		for (int i = count = 0; i < WORD_LENGTH_BITS; i++, num <<= 1)
			System.out.print((((num & (1 << WORD_LENGTH_BITS)) != 0) ? "1" : "0") +
					((++count % 4 == 0) ? " " : ""));
		System.out.print("\n");
	}


	@Override
	protected void reset() {
		/*
		 * Do not reset/clear here ras or activeFunctionCallStats.
		 */
		if (debug) {
			System.out.println("ToolReset");
		}
		refreshGui();
	}


	/**
	 * Refresh memory contents and $sp position in table.
	 */
	private synchronized void refreshGui() {
		getStackData();
		updateSpDataRowColIndex();
		table.repaint();
	}


	/**
	 * Update data table indexes ({@code spDataRowIndex},{@code spDataColumnIndex})
	 * as of where $sp points to.
	 */
	private void updateSpDataRowColIndex() {
		int spValue = getSpValue();
		try {
			spDataRowIndex = getTableRowIndex(spValue);
			spDataColumnIndex = getTableColumnIndex(spValue);
		} catch (SVException sve) {
			System.err.println("updateSpDataRowColIndex(): " + sve.getMessage());
		}
	}


	/**
	 * Empties the contents of columns "Stored Reg" and "Call Layout" between
	 * rows [{@code startRow}, {@code endRow}].
	 */
	private synchronized void resetStoredRegAndFrameNameColumns(int startRow, int endRow) {
		for (int row = startRow; row <= endRow; row++) {
			tableModel.setValueAt("", row, storedRegisterColumn);
			tableModel.setValueAt("", row, frameNameColumn);
		}
	}


	/**
	 * Calculate table column index when offset from last table column is provided.
	 *
	 * @param offsetFromEnd the offset from last table column (starting from 0).
	 * @return the table column index.
	 */
	private int calcTableColIndex(int offsetFromEnd) {
		return numberOfColumns - offsetFromEnd - 1;
	}


	/**
	 * A {@link SimulatorNotice} is handled locally in {@link StackVisualizer}, while all
	 * other notices are handled by supertype {@link AbstractMarsToolAndApplication}.
	 */
	@Override
	public void update(Observable observable, Object accessNotice) {
		if (observable == mars.simulator.Simulator.getInstance()) {
			processSimulatorUpdate((SimulatorNotice) accessNotice);
		} else {
			super.update(observable, accessNotice);
		}
	}


	/**
	 * Process a {@link SimulatorNotice} and handle {@code SIMULATOR_START} or
	 * {@code SIMULATOR_STOP} accordingly.
	 */
	private void processSimulatorUpdate(SimulatorNotice notice) {
		int action = notice.getAction();
		if (debug)
			System.out.println("\nSimulatorNotice: " + ((action == SimulatorNotice.SIMULATOR_START) ? "Start" : "End"));
		if (action == SimulatorNotice.SIMULATOR_START)
			onSimulationStart();
		else if (action == SimulatorNotice.SIMULATOR_STOP)
			onSimulationEnd();
	}


	/**
	 * Checks if {@code MemoryConfiguration} has changes and if yes,
	 * performs the required actions.
	 */
	private void checkMemConfChanged() {
		int newStackBaseAddr = Memory.stackBaseAddress;
		if (newStackBaseAddr != currStackBaseAddress) {
			if (debug) {
				System.out.println("Memory configuration change detected. New stack base address: "
						+ formatAddress(newStackBaseAddr));
			}
			currStackBaseAddress = newStackBaseAddr;
			maxSpValueWordAligned = Memory.stackPointer;
			if (isObserving()) {
				/*
				 * Without the isObserving check, in case memory configuration change is detected
				 * when the tool is not connected, connecting it will result in observing two times
				 * each resource and receiving two times the same access notices.
				 */
				synchronized (Globals.memoryAndRegistersLock) {
					deleteAsObserver();
					addAsObserver(); /* Start observing the new resources */
				}
				refreshGui();
			}
		}
	}

	/**
	 * Callback method after a simulation starts.
	 * A simulation starts each time a Run button is pressed (stepped or not).
	 */
	private synchronized void onSimulationStart() {
		if (!isObserving())
			return;
		if (VenusUI.getReset()) { // GUI Reset button clicks are also handled here.
			if (debug)
				System.out.println("GUI registers/memory reset detected");
			 /* On memory configuration changes, the registers are reset. */
			checkMemConfChanged();
			/*
			 * On registers/memory reset, clear data related to subroutine calls,
			 * and reset/update table data.
			 */
			ras.clear();
			activeFunctionCallStats.reset();
			tableModel.setRowCount(INITIAL_ROW_COUNT);
			numberOfRows = tableModel.getRowCount();
			resetStoredRegAndFrameNameColumns(0, numberOfRows-1);
			refreshGui();
		}
		disableBackStepper();
		onSimStartEndSetEnabled(false);
	}


	/**
	 * Callback method after a simulation ends.
	 * A simulation starts/ends each time a Run button is pressed (stepped or not).
	 */
	private void onSimulationEnd() {
		if (!isObserving())
			return;
		onSimStartEndSetEnabled(true);
	}


	/**
	 * Enables/disables GUI components on simulation start/end.
	 */
	private void onSimStartEndSetEnabled(boolean enable) {
		jalEquivInstrCheckBox.setEnabled(enable);
		if (inStandAloneMode == false) {
			/* Connect button is not available in stand-alone mode. */
			connectButton.setEnabled(enable);
		}
	}


	/**
	 * @return true if we are in stepped execution.
	 */
	@SuppressWarnings("unused")
	private boolean inSteppedExecution() {
		if (Globals.program == null)
			return false;
		return Globals.program.inSteppedExecution();
	}


	@Override
	protected JComponent getHelpComponent() {
		final String helpContent = "Stack Visualizer\n\n"
				+ "About\n"
				+ "This tool allows the user to view in real time the memory modification operations taking place in the stack\n"
				+ "segment with emphasis to $sp-relative memory accesses. The user can also observe how pushes/pops to/from the\n"
				+ "stack take place. The address pointed by the stack pointer is displayed in a highlighted background while the whole\n"
				+ "word-length data in yellow. Lower addresses have a grey background (given that stack growth takes place\n"
				+ "from higher to lower addresses). The names of the registers whose contents are stored (sw, sh, sb etc.) in the\n"
				+ "stack, are shown in the \"Stored Reg\" column.";
		JButton help = new JButton("Help");
		help.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent e) {
				showMessageWindow(helpContent);
			}
		});
		return help;
	}


	private void showMessageWindow(String message) {
		JOptionPane.showMessageDialog(theWindow, message);
	}


	/**
	 * Active subroutine call statistics.
	 */
	private static class ActiveSubroutineStats {
		private HashMap<String, Integer> activeCalls;

		public ActiveSubroutineStats() {
			activeCalls = new HashMap<>(0);
		}

		/**
		 * Adds one more subroutine call to statistics.
		 *
		 * @param subroutineName name of subroutine to be added.
		 * @return the number of active subroutine calls of {@code subroutineName}.
		 */
		public Integer addCall(String subroutineName) {
			Integer newValue;
			if (activeCalls.containsKey(subroutineName)) {
				newValue = activeCalls.get(subroutineName) + 1;
				activeCalls.replace(subroutineName, newValue);
			} else {
				activeCalls.put(subroutineName, 1);
				newValue = 1;
			}
			return newValue;
		}

		/**
		 * Removes one subroutine call from statistics.
		 *
		 * @param subroutineName name of subroutine to be removed.
		 */
		public void removeCall(String subroutineName) {
			Integer oldValue = activeCalls.get(subroutineName);
			if (oldValue == null)
				System.err.println("ActiveFunctionCallStats.removeCall: " + subroutineName + " doesn't exist");
			else
				activeCalls.replace(subroutineName, oldValue - 1);
		}

		/**
		 * Reset active call statistics.
		 */
		public void reset() {
			activeCalls.clear();
		}
	}


	/**
	 * Trivial {@link Exception} implementation for {@link StackVisualizer}.
	 */
	private static class SVException extends Exception {
		public SVException(String message) {
			super(message);
		}
	}

}