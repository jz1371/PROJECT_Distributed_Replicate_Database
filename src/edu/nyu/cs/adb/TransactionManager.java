package edu.nyu.cs.adb;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Map;
import java.util.HashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.Queue;
import java.util.Set;

public class TransactionManager {

  // Global time stamp
  private int timestamp;

  // General input reader
  private BufferedReader br;

  // General output writer
  private BufferedWriter bw;

  // indicate whether use standard output or not
  private boolean stdout;

  // trace whether there is a transaction abort or commit
  private boolean commitOrAbort;

  // Map<Transaction id, Transaction>.
  private Map<Integer, Transaction> transactions = new HashMap<Integer, Transaction>();

  private List<DatabaseManager> databaseManagers;

  // Map<Variable index, List of sites storing this variable>.
  private Map<Integer, List<Integer>> variableMap;

  // List of transaction id that have been aborted.
  private Set<Integer> abortedTransactions = new HashSet<Integer>();

  // List of transaction id that have committed.
  private Set<Integer> committedTransactions = new HashSet<Integer>();

  // Queue of all waiting operations.
  private Queue<Operation> waitingOperations = new LinkedList<Operation>();

  /**
   * Constructor with standard input .
   */
  public TransactionManager() {
    br = new BufferedReader(new InputStreamReader(System.in));
    bw = new BufferedWriter(new OutputStreamWriter(System.out));
    stdout = true;
  }

  /**
   * Constructor with file input .
   */
  public TransactionManager(String inputFile) {
    try {
      br = new BufferedReader(new FileReader(inputFile));
      bw = new BufferedWriter(new OutputStreamWriter(System.out));
      stdout = true;
    } catch (Exception e) {
      System.err.println(e.getMessage());
    }
  }

  /**
   * Constructor with standard input .
   */
  public TransactionManager(String inputFile, String outputFile) {
    br = new BufferedReader(new InputStreamReader(System.in));
    try {
      bw = new BufferedWriter(new FileWriter(outputFile));
    } catch (IOException e) {
      e.printStackTrace();
    }
    stdout = false;
  }

  /**
   * Return the current time stamp.
   * 
   * @return current time stamp.
   */
  public int getCurrentTime() {
    return timestamp;
  }

  /**
   * Check whether there is any running READ_ONLY transaction.
   * 
   * @return true if there is running READ_ONLY transaction.
   */
  public boolean hasRunningReadonly() {
    for (Integer tid : transactions.keySet()) {
      if (transactions.get(tid).getType() == Transaction.Type.RO
          && !committedTransactions.contains(tid)
          && !abortedTransactions.contains(tid)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Initialize database managers of the given number.
   * 
   * @param nDatabaseManager
   *          the number of database managers to be initialized.
   */
  public void init(int nDatabaseManager) {
    timestamp = 0;
    databaseManagers = new ArrayList<DatabaseManager>();
    variableMap = new HashMap<Integer, List<Integer>>();
    for (int index = 1; index <= nDatabaseManager; index++) {
      DatabaseManager dm = new DatabaseManager(index, this);
      dm.init();
      databaseManagers.add(dm);
    }
    for (int index = 1; index <= 20; index++) {
      List<Integer> sites = new ArrayList<Integer>();
      if (index % 2 == 1) {
        // store odd variable at (1 + index mod 10) site
        sites.add(1 + index % 10);
      } else {
        // even variable are stored in all sites.
        for (int i = 1; i <= 10; i++) {
          sites.add(i);
        }
      }
      variableMap.put(index, sites);
    }
  }

  /**
   * Read contents from standard input or input file. Parse instructions, and
   * then execute operations accordingly.
   */
  public void run() {
    try {
      while (true) {
        commitOrAbort = false;
        String line = br.readLine();
        if (line == null || line.contains("exit"))
          break;
        if (line.startsWith("//"))
          continue;
        if (!line.isEmpty()) {
          List<Operation> operations = parseLine(line);
          batchExecute(operations);
        }

        // If there is a commit or abort, re-issue all the waiting
        // operations
        while (commitOrAbort == true) {
          commitOrAbort = false;
          for (int i = 0; i < waitingOperations.size(); i++) {
            execute(waitingOperations.poll());
          }
        }
        timestamp++;
      }
      br.close();
      bw.close();
    } catch (IOException e) {
      System.err.println(e.getMessage());
    }
  }

  /**
   * Parse line into list of operations. Execute instructions for "begin",
   * "end", "fail", "recover" immediately.
   */
  private List<Operation> parseLine(String line) {
    String[] instructions = line.replaceAll("\\s+", "").split(";");
    List<Operation> result = new ArrayList<Operation>();
    for (String instruction : instructions) {
      String token = "";
      String arg = "";
      int tokenEnd = instruction.indexOf("(");
      int argsEnd = instruction.indexOf(")");
      if (tokenEnd == -1 || argsEnd == -1) {
        try {
          bw.write("Unexpected: " + instruction);
          bw.newLine();
          if (stdout) {
            bw.flush();
          }
        } catch (IOException e) {
          e.printStackTrace();
        }
        break;
      }
      token = instruction.substring(0, tokenEnd);
      arg = instruction.substring(tokenEnd + 1, argsEnd);
      if (token.equals("begin")) {
        beginTransaction("RW", arg);
      } else if (token.equals("beginRO")) {
        beginTransaction("RO", arg);
      } else if (token.equals("end")) {
        endTransaction(arg);
      } else if (token.equals("fail")) {
        fail(parseSiteIndex(arg));
      } else if (token.equals("recover")) {
        recover(parseSiteIndex(arg));
      } else if (token.equals("R")) {
        result.add(parseReadOperation(arg));
      } else if (token.equals("W")) {
        result.add(parseWriteOperation(arg));
      } else if (token.equals("dump")) {
        parseDump(arg);
      } else if (token.equals("querystate")) {
        queryState();
      } else if (token.equals("clear")) {
        restart();
      } else {
        try {
          bw.write("Unexpected input: " + instruction);
          bw.newLine();
          if (stdout) {
            bw.flush();
          }
        } catch (IOException e) {
          e.printStackTrace();
        }
      }
    }
    return result;
  }

  /*
   * Parse transaction id from tidStr and then create new transaction.
   * 
   * @param type READ_ONLY or READ_WRITE
   * 
   * @param tidStr string that containing transaction id.
   */
  public void beginTransaction(String type, String tidStr) {
    int tid = parseTransactionId(tidStr);
    if (transactions.containsKey(tid))
      return;
    if (type == "RO") {
      transactions.put(tid, new Transaction(tid, timestamp,
          Transaction.Type.RO));
    } else {
      transactions.put(tid, new Transaction(tid, timestamp,
          Transaction.Type.RW));
    }
  }

  /*
   * Notify database managers to commit given transaction if that transaction
   * has not been aborted and put that into committed list. If RO commits and no
   * more RO left, then clear all the copies in DMs.
   * 
   * @param tidStr
   */
  public void endTransaction(String tidStr) {
    int tid = parseTransactionId(tidStr);
    if (!hasAborted(tid)) {
      for (DatabaseManager dm : databaseManagers) {
        if (dm.getStatus()) {
          dm.commit(tid);
        }
      }
      commitOrAbort = true;
      committedTransactions.add(tid);
      if (transactions.containsKey(tid)) {
        if (transactions.get(tid).getType() == Transaction.Type.RO
            && !hasRunningReadonly()) {
          for (DatabaseManager dm : databaseManagers) {
            dm.clearAllVersions();
          }
        }
      }
    }
  }

  /*
   * Let the site at given index fail. Abort all transactions that have accessed
   * that site immediately.
   * 
   * @param siteIndex
   */
  public void fail(int siteIndex) {
    List<Integer> accessedTransactions = databaseManagers.get(siteIndex - 1)
        .getAccessedTransaction();
    for (Integer tid : accessedTransactions) {
      abortedTransactions.add(tid);
      abort(tid);
    }
    databaseManagers.get(siteIndex - 1).fail();
  }

  /*
   * Recovery site at given index.
   * 
   * @param index
   */
  public void recover(int index) {
    databaseManagers.get(index - 1).recover();
  }

  /* Restart database, clear current states. */
  public void restart() {
    init(10);
    timestamp = -1;
    transactions.clear();
    committedTransactions.clear();
    abortedTransactions.clear();
    waitingOperations.clear();
  }

  /*
   * Print out current query state: committed transactions, aborted
   * transactions, and running transactions.
   */
  public void queryState() {
    try {
      bw.write("Transactions committed:\n");
      for (Integer t : committedTransactions) {
        bw.write("T" + t + " ");
      }
      bw.write("\n");
      bw.write("Transactions aborted:\n");
      for (Integer t : abortedTransactions) {
        bw.write("T" + t + " ");
      }
      bw.write("\n");
      bw.write("Transactions still running:\n");
      for (Integer tid : transactions.keySet()) {
        if (!committedTransactions.contains(tid)
            && !abortedTransactions.contains(tid)) {
          bw.write("T" + tid + " ");
        }
      }
      bw.write("\n");
      if (stdout) {
        bw.flush();
      }
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  /*
   * Execute a batch of operations.
   * 
   * @param operations
   */
  private void batchExecute(List<Operation> operations) {
    for (Operation oper : operations) {
      execute(oper);
    }
  }

  /*
   * Execute a single operation.
   * 
   * @param operation
   */
  private void execute(Operation oper) {
    if (oper.getType() == Operation.Type.READ) {
      read(oper);
    } else {
      write(oper);
    }
  }

  /**
   * 
   * @param oper
   */
  public void write(Operation oper) {
    if (hasAborted(oper.getTranId())) {
      return;
    }
    boolean writable = true;
    boolean allSitesDown = true;
    int varIndex = oper.getVarIndex();
    Set<Integer> conflictTranSet = new HashSet<Integer>();
    List<Integer> sites = getSites(varIndex);
    for (Integer siteIndex : sites) {
      DatabaseManager dm = databaseManagers.get(siteIndex - 1);
      if (dm.getStatus()) {
        allSitesDown = false;
        if (!dm.isWritable(oper.getTranId(), varIndex)) {
          writable = false;
          conflictTranSet.addAll(dm.getConflictTrans(varIndex));
        }
      }
    }
    if (writable && !allSitesDown) {
      for (Integer siteIndex : sites) {
        DatabaseManager dm = databaseManagers.get(siteIndex - 1);
        if (dm.getStatus()) {
          dm.write(transactions.get(oper.getTranId()), varIndex,
              oper.getWriteValue());
        }
      }
    } else {
      int oldest = getOldestTime(conflictTranSet);
      waitDieProtocol(oper, oldest);
    }
  }

  private int getOldestTime(Set<Integer> conflictTranSet) {
    int result = timestamp + 1;
    Iterator<Integer> it = conflictTranSet.iterator();
    while (it.hasNext()) {
      int time = transactions.get(it.next()).getTimestamp();
      if (time < result) {
        result = time;
      }
    }
    return result;
  }

  /**
   * 
   * @param operation
   */
  public void read(Operation operation) {
    // ignore operation if aborted
    if (hasAborted(operation.getTranId())) {
      return;
    }
    int varIndex = operation.getVarIndex();
    List<Integer> sites = getSites(varIndex);
    for (Integer siteIndex : sites) {
      DatabaseManager dm = databaseManagers.get(siteIndex - 1);
      if (dm.getStatus()) {
        Data data = dm.read(transactions.get(operation.getTranId()), varIndex);
        if (data != null) {
          System.out
              .println("T" + operation.getTranId() + " reads x" + varIndex
                  + " " + data.getValue() + " at site " + dm.getIndex());
          return;
        } else if (dm.getConflictTrans(varIndex) != null
            && dm.getConflictTrans(varIndex).size() != 0) {
          Iterator<Integer> it = dm.getConflictTrans(varIndex).iterator();
          int tid = it.next();
          waitDieProtocol(operation, transactions.get(tid).getTimestamp());
          return;
        }
      }
    }
    waitingOperations.offer(operation);
  }

  /*
   * If the transaction associated with given operation is older than given t,
   * then given operation should "wait". Otherwise, abort transaction.
   * 
   * @param oper
   * 
   * @param t
   */
  private boolean waitDieProtocol(Operation oper, int t) {
    if (transactions.get(oper.getTranId()).getTimestamp() < t) {
      // should wait
      waitingOperations.offer(oper);
      return true;
    } else {
      abort(oper.getTranId());
      return false;
    }
  }

  // Parse dump-related commands.
  private void parseDump(String arg) {
    if (arg.equals("")) {
      dump();
    } else if (arg.startsWith("x")) {
      dumpVar(parseVariable(arg));
    } else {
      dumpSite(parseSiteIndex(arg));
    }
  }

  // Print all committed values af each variable at each site.
  public void dump() {
    for (DatabaseManager dm : databaseManagers) {
      try {
        bw.write("Site: " + dm.getIndex());
        bw.newLine();
        if (stdout) {
          bw.flush();
        }
      } catch (IOException e) {
        e.printStackTrace();
      }
      dumpSite(dm.getIndex());
    }
  }

  // Print all committed values of all variables at given site.
  public void dumpSite(int siteIndex) {
    Map<Integer, Data> siteVars = databaseManagers.get(siteIndex - 1)
        .getDataMap();
    List<Integer> indexList = new ArrayList<Integer>(siteVars.keySet());
    Collections.sort(indexList);
    for (Integer varIndex : indexList) {
      try {
        bw.write("x" + varIndex + ": " + siteVars.get(varIndex).getValue());
        bw.newLine();
        if (stdout) {
          bw.flush();
        }
      } catch (IOException e) {
        e.printStackTrace();
      }
    }
  }

  // Print all committed values of given variable.
  public void dumpVar(int varIndex) {
    for (DatabaseManager dm : databaseManagers) {
      Data data = dm.dump(varIndex);
      if (data != null) {
        try {
          bw.write("x" + varIndex + ": " + data.getValue() + " at site "
              + dm.getIndex());
          bw.newLine();
          if (stdout) {
            bw.flush();
          }
        } catch (IOException e) {
          e.printStackTrace();
        }
      }
    }
  }

  /*
   * Notify database managers to abort given transaction and put that
   * transaction put into aborted list.
   */
  public void abort(int tid) {
    for (DatabaseManager dm : databaseManagers) {
      if (dm.getStatus()) {
        dm.abort(tid);
      }
    }
    abortedTransactions.add(tid);
    commitOrAbort = true;
  }

  // Return all sites that storing given variable.
  private List<Integer> getSites(int varIndex) {
    return variableMap.get(varIndex);
  }

  private boolean hasAborted(int tid) {
    return abortedTransactions.contains(tid);
  }

  // Parse transaction id from "T*" string
  private int parseTransactionId(String s) {
    return Integer.parseInt(s.substring(1));
  }

  // Parse site index out of string.
  private int parseSiteIndex(String s) {
    return Integer.parseInt(s);
  }

  // Parse variable index from "x*"
  private int parseVariable(String s) {
    return Integer.parseInt(s.substring(1));
  }

  // Parse "T*, x*" into corresponding read operation
  private Operation parseReadOperation(String arg) {
    String[] args = arg.split(",");
    check(args.length == 2, "Unexpected Read " + arg);
    int tid = parseTransactionId(args[0]);
    int var = parseVariable(args[1]);
    return new Operation(tid, var, timestamp, Operation.Type.READ);
  }

  // Parse "T*, x*, **" into corresponding write operation
  private Operation parseWriteOperation(String arg) {
    String[] args = arg.split(",");
    check(args.length == 3, "Unexpected Write " + arg);
    int tid = parseTransactionId(args[0]);
    int var = parseVariable(args[1]);
    int writeValue = Integer.parseInt(args[2]);
    return new Operation(tid, var, timestamp, Operation.Type.WRITE, writeValue);
  }

  // If condition is false, print out error message and exit program
  private void check(boolean condition, String errMsg) {
    if (!condition) {
      System.err.println(errMsg);
      System.exit(-2);
    }
  }
}
