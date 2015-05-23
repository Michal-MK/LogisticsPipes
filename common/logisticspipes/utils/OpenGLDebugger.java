package logisticspipes.utils;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ScrollPaneConstants;
import javax.swing.UIManager;
import javax.swing.UnsupportedLookAndFeelException;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.TableModel;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import lombok.Getter;
import lombok.Setter;
import org.lwjgl.opengl.GL11;

@SuppressWarnings("unused") public class OpenGLDebugger {

	private static HashMap<Integer, String> niceToHave = null;
	private static int probeID = 0;
	private Thread probeGUIThread;
	private int cycleCount;
	private boolean started;
	private ConcurrentHashMap<Integer, Object> glVariables;
	private ConcurrentHashMap<Integer, GLTypes> glVariablesToCheck;
	private final Lock debuggerLock;
	private final Condition glVariablesCondition;
	private boolean glVariablesUpdated;
	@Getter @Setter private int printOnCycle;

	private enum GLTypes {
		BOOLEAN("boolean", "GL11.glGetBoolean"),
		DOUBLE("double", "GL11.glGetDouble"),
		FLOAT("float", "GL11.glGetFloat"),
		INTEGER("int", "GL11.glGetInteger"),
		INTEGER64("long", "GL32.glGetInteger64");

		private String getterFunction;
		private String niceName;

		GLTypes(String niceName, String getterFunction) {
			this.niceName = niceName;
			this.getterFunction = getterFunction;
		}
	}

	public class ProbeGUI extends JDialog implements Runnable {

		private JPanel mainPanel;
		private JTable variableMonitorTable;
		private JButton closeButton;
		private JButton addButton;
		private JTextField addTextField;
		private JScrollPane monitorTableScrollPane;

		private ArrayList<Integer> tableList;

		public ProbeGUI() {
			for (UIManager.LookAndFeelInfo info : UIManager.getInstalledLookAndFeels()) {
				if ("Windows".equals(info.getName())) {
					try {
						UIManager.setLookAndFeel(info.getClassName());
					} catch (ReflectiveOperationException e) {
						e.printStackTrace();
					} catch (UnsupportedLookAndFeelException e) {
						e.printStackTrace();
					}
					break;
				}
			}

			setupUI();

			setType(Type.UTILITY);
			setContentPane(mainPanel);
			getRootPane().setDefaultButton(closeButton);

			tableList = new ArrayList<Integer>();

			TableModel glVariableDataModel = new AbstractTableModel() {

				@Override public int getRowCount() {
					return tableList.size();
				}

				@Override public int getColumnCount() {
					return 2;
				}

				@Override public Object getValueAt(int rowIndex, int columnIndex) {
					try {
						switch (columnIndex) {
							case 0:
								return niceToHave.get(tableList.get(rowIndex));
							case 1:
								return glVariables.get(tableList.get(rowIndex));
							default:
								return "<NOVALUE>";
						}
					} catch (IndexOutOfBoundsException e) {
						return "<EXCEPTION>";
					}
				}
			};
			variableMonitorTable.setModel(glVariableDataModel);

			setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
			addWindowListener(new WindowAdapter() {

				public void windowClosing(WindowEvent e) {
					stop();
				}
			});

			mainPanel.registerKeyboardAction(new ActionListener() {

				public void actionPerformed(ActionEvent e) {
					stop();
				}
			}, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT);
		}

		@Override public void run() {
			for (Integer key : niceToHave.keySet()) {
				glVariablesToCheck.put(key, GLTypes.BOOLEAN);
			}
			pack();
			setVisible(true);

			while (started) {
				debuggerLock.lock();
				try {
					while (!glVariablesUpdated) {
						glVariablesCondition.await();
					}
					glVariablesUpdated = false;
					updateVariables();
				} catch (InterruptedException e) {
					e.printStackTrace();
				} finally {
					debuggerLock.unlock();
				}
			}
		}

		private void updateVariables() {
			tableList = new ArrayList<Integer>(glVariables.keySet());
			Collections.sort(tableList);

			variableMonitorTable.updateUI();
		}

		private void setupUI() {
			mainPanel = new JPanel();
			mainPanel.setLayout(new GridBagLayout());
			mainPanel.setBorder(BorderFactory.createTitledBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5), null));
			closeButton = new JButton();
			closeButton.setText("Close");
			GridBagConstraints gbc;
			gbc = new GridBagConstraints();
			gbc.gridx = 2;
			gbc.gridy = 1;
			gbc.fill = GridBagConstraints.HORIZONTAL;
			gbc.insets = new Insets(0, 5, 0, 0);
			mainPanel.add(closeButton, gbc);
			addButton = new JButton();
			addButton.setText("Add");
			gbc = new GridBagConstraints();
			gbc.gridx = 1;
			gbc.gridy = 1;
			gbc.fill = GridBagConstraints.HORIZONTAL;
			gbc.insets = new Insets(0, 5, 0, 0);
			mainPanel.add(addButton, gbc);
			addTextField = new JTextField();
			addTextField.setText("");
			gbc = new GridBagConstraints();
			gbc.gridx = 0;
			gbc.gridy = 1;
			gbc.weightx = 1.0;
			gbc.anchor = GridBagConstraints.WEST;
			gbc.fill = GridBagConstraints.HORIZONTAL;
			mainPanel.add(addTextField, gbc);
			monitorTableScrollPane = new JScrollPane();
			monitorTableScrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
			gbc = new GridBagConstraints();
			gbc.gridx = 0;
			gbc.gridy = 0;
			gbc.gridwidth = 3;
			gbc.weightx = 1.0;
			gbc.weighty = 1.0;
			gbc.fill = GridBagConstraints.BOTH;
			gbc.insets = new Insets(0, 0, 5, 0);
			mainPanel.add(monitorTableScrollPane, gbc);
			variableMonitorTable = new JTable();
			variableMonitorTable.setEnabled(false);
			monitorTableScrollPane.setViewportView(variableMonitorTable);
		}
	}

	public OpenGLDebugger(int printOnCycle) {
		if (printOnCycle < 1) {
			throw new IllegalArgumentException("Print per cycle must be at least 1");
		}

		if (niceToHave == null) {
			updateNiceToHave();
		}

		debuggerLock = new ReentrantLock();
		glVariablesCondition = debuggerLock.newCondition();

		this.printOnCycle = printOnCycle;
		this.glVariables = new ConcurrentHashMap<Integer, Object>();
		this.glVariablesToCheck = new ConcurrentHashMap<Integer, GLTypes>();

		this.probeGUIThread = new Thread(new ProbeGUI(), "LogisticsPipes GLDebug Probe #" + probeID);
		probeID++;
	}

	public void start() {
		started = true;
		cycleCount = 0;
		probeGUIThread.start();
	}

	public void stop() {
		debuggerLock.lock();
		try {
			started = false;
			glVariablesUpdated = true;
			glVariablesCondition.signal();
		} finally {
			debuggerLock.unlock();
		}
	}

	public void cycle() {
		if (started) {
			++cycleCount;
			if (cycleCount % printOnCycle == 0) {
				saveOpenGLStuff();
				cycleCount = 0;
			}
		}
	}

	private void saveOpenGLStuff() {
		debuggerLock.lock();
		try {
			Iterator<Integer> i = glVariablesToCheck.keySet().iterator();
			while (i.hasNext()) {
				Integer key = i.next();
				Object value = GL11.glGetBoolean(key);
				if (GL11.glGetError() == GL11.GL_INVALID_ENUM) {
					i.remove();
				} else {
					glVariables.put(key, value);
				}
			}
			glVariablesUpdated = true;
			glVariablesCondition.signal();
		} finally {
			debuggerLock.unlock();
		}
	}

	private static void updateNiceToHave() {
		niceToHave = new HashMap<Integer, String>();
		int crawlerVersion = 11;
		boolean almostEnd = false;
		boolean end = false;
		while (!end) {
			String packageGL = String.format("%s%d", "GL", crawlerVersion);
			String nextGL = String.format("%s.%s", "org.lwjgl.opengl", packageGL);
			try {
				crawlerVersion++;
				Class glClass = GL11.class.getClassLoader().loadClass(nextGL);
				com.google.common.reflect.Reflection.initialize(glClass);
				almostEnd = false;

				for (Field f : glClass.getDeclaredFields()) {
					try {
						int id = f.getInt(null);
						String nice = f.getName();
						if (nice.endsWith("BIT")) {
							continue;
						}
						/*
						// All the things that are being replaced are not that bad
						if (niceToHave.containsKey(id) && !niceToHave.get(id).equals(nice)) {
							System.out.printf("NiceToHave: ID %d exists. Replacing %s with %s!!%n", id, niceToHave.remove(id), nice);
						}
						*/
						niceToHave.put(id, String.format("%s.%s", packageGL, nice));
					} catch (IllegalArgumentException e) {
						System.out.printf("NiceToHave: Illegal Argument!%nNiceToHave: %s%n", e);
					} catch (IllegalAccessException e) {
						System.out.printf("NiceToHave: Illegal Access!%nNiceToHave: %s%n", e);
					}
				}
			} catch (ClassNotFoundException e) {
				if (almostEnd) {
					end = true;
				} else {
					almostEnd = true;
					crawlerVersion = (crawlerVersion / 10 + 1) * 10;
				}
			}
		}
	}
}
