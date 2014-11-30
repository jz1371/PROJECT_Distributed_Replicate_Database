package edu.nyu.cs.adb;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.HashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.Set;

public class TransactionManager {
  
  private int timestamp;
  
  private Map<Integer, Transaction> transactions = 
      new HashMap<Integer, Transaction>();
  
  private List<DatabaseManager> databaseManagers;
  
  private Map<Integer, List<Integer>> variableMap;
  
  private List<Integer> abortedTransactions = 
      new ArrayList<Integer>();
  
  private List<Integer> committedTransactions = 
      new ArrayList<Integer>();

  private List<Operation> waitingOpeartions =
      new ArrayList<Operation>();
  
  /**
   * Return the current time stamp.
   * @return
   */
  public int getCurrentTime() {
    return timestamp;
  }
  
  /**
   * Check whether there is any running READ_ONLY transaction. 
   * @return
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
   * Constructor initializing database managers.
   */
  public TransactionManager() {
    int nDatabaseManager = 10;
    initialize(nDatabaseManager);
  }
  
  /**
   * Initialize database managers of the given number
   * @param nDatabaseManager the number of database managers 
   * to be initialized.
   */
  private void initialize(int nDatabaseManager) {
    timestamp = 0;
    for (int index = 1; index <= nDatabaseManager; index++) {
      DatabaseManager dm = new DatabaseManager(index, null);
      dm.init();
      databaseManagers.add(dm);
    }
    for (int index = 1 ; index <= 20 ; index++) {
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
   * Read content from input file, parse instructions from each line, 
   * and run instructions accordingly.
   * @param inputFile
   */
  public void run(String inputFile) {
    try {
      BufferedReader br = new BufferedReader(new FileReader(inputFile));
      while (true) {
        timestamp++;
        //TODO: try to do waiting operations.
        for (Operation operation : waitingOpeartions) {
          execute(operation);
        }
        String line = br.readLine();
        if (line == null) break;
        List<Operation> operations = parseLine(line);
        for (Operation operation : operations) {
          execute(operation);
        }
      }
      br.close();
    } catch (Exception e) {
      System.err.println(e.getMessage());
    }
  }
  
  /** 
   * Parse line into list of instructions.Execute instruction 
   * for "begin", "end", "fail", "recover" immediately.
   */
  private List<Operation> parseLine(String line) {
    String[] instructions = line.split(";");
    List<Operation> result = new ArrayList<Operation>();
    for (String instruction: instructions) {
      int tokenEnd = instruction.indexOf("(");
      String token = instruction.substring(0, tokenEnd);
      int argsEnd = instruction.indexOf(")");
      String arg = instruction.substring(tokenEnd + 1, argsEnd);
      if (token == "begin") {
        beginTransaction("RW", arg);
      } else if (token == "beginRO") {
        beginTransaction("RO", arg);
      } else if (token == "end") {
        endTransaction(arg);
      } else if (token == "fail") {
        fail(parseSiteIndex(arg));
      } else if (token == "recover") {
        recover(parseSiteIndex(arg));
      } else if (token == "R") {
        result.add(parseReadOperation(arg));
      } else if (token == "W") {
        result.add(parseWriteOperation(arg));
      } else if (token == "dump") {
        //TODO: not implemented
      } else {
        check(false, "Unexpected input: " + instruction);
      }
    }
    return result;
  }
  
  private void execute(Operation operation) {
    if (operation.getType() == Operation.Type.READ) {
      read(operation);
    } else {
      write(operation);
    }
  }
  
  /**
   * 
   * @param oper
   */
  private void write(Operation oper) {
    if (hasAborted(oper.getTranId())) return; 
    boolean writable = true;
    int varIndex = oper.getVarIndex();
    Set<Integer> conflictTranSet = new HashSet<Integer>();
    List<Integer> sites = getSites(varIndex);
    for (Integer siteIndex : sites) {
      DatabaseManager dm = databaseManagers.get(siteIndex);
      //TODO: assume not all sites are down
      if ( (dm.getStatus()) &&
          (!dm.isWritable(transactions.get(oper.getTranId()), varIndex)) ) {
        writable = false;
        conflictTranSet.addAll(dm.getConflictTrans(varIndex));
      }
    }
    if (writable) {
      for (DatabaseManager dm : databaseManagers) {
        dm.write(transactions.get(
            oper.getTranId()), varIndex, oper.getWriteValue());
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
  private void read(Operation operation) {
    // ignore operation if aborted
    if (hasAborted(operation.getTranId())) {
      return;
    }
    int varIndex = operation.getVarIndex();
    List<Integer> sites = getSites(varIndex);
    for (Integer siteIndex : sites) {
      DatabaseManager dm = databaseManagers.get(siteIndex);
      if (dm.getStatus()) {
        Data data = dm.read(transactions.get(operation.getTranId()), varIndex);
        if (data != null) {
          //TODO: where to store the value that have been read
          operation.setWriteValue(data.getValue());
          return;
        } else if (dm.getConflictTrans(varIndex) != null) {
          //TODO: wait or abort
          Iterator<Integer> it = dm.getConflictTrans(varIndex).iterator();
          int tid = it.next();
          waitDieProtocol(operation, transactions.get(tid).getTimestamp());
          return;
        }
      }
    }
    waitingOpeartions.add(operation);
  }
  
  /**
   * 
   * @param oper
   * @param t
   */
  private void waitDieProtocol(Operation oper, int t) {
    if (waitOrDie(oper.getTranId(), t) == "wait") {
      waitingOpeartions.add(oper);
    } else {
      abort(transactions.get(oper.getTranId()));
    }
  }
  
  /**
   * If transaction of given id has smaller time stamp than given time stamp,
   * then this transaction should wait. Otherwise, abort this transaction.
   * @param t
   * @param timestamp
   * @return "wait" or "die"
   */
  private String waitOrDie(int tid, int timestamp) {
    return (transactions.get(tid).getTimestamp() < timestamp) ? "wait" : "die";
  }
  
  /**
   * Notify database managers to abort given transaction and
   * put that transaction put into aborted list.
   * @param t
   */
  private void abort(Transaction t) {
    for (DatabaseManager dm : databaseManagers) {
      dm.abort(t);
    }
    abortedTransactions.add(t.getTranId());
  }
  
  /** 
   * Parse transaction id from tidStr and then create new transaction. 
   * @param type
   * @param tidStr
   */
  private void beginTransaction(String type, String tidStr) {
    //TODO: make sure argument is of  ^T[0-9]+$
    int tid = parseTransactionId(tidStr);
    //TODO: what if that transaction has finished but still in map ?
    if (transactions.containsKey(tid)) return;
    if (type == "RO") {
      transactions.put(tid, 
          new Transaction(tid, timestamp, Transaction.Type.RO));
    } else {
      transactions.put(tid, 
          new Transaction(tid, timestamp, Transaction.Type.RW));
    }
  }
  
  /**
   * Notify database managers to commit given transaction if that 
   * transaction has not been aborted. And put that into committed list. 
   */ 
  private void endTransaction(String tidStr) {
    //TODO: make sure argument is of  ^T[0-9]+$
    int tid = parseTransactionId(tidStr);
    if (!hasAborted(tid)) {
      for (DatabaseManager dm : databaseManagers) {
        dm.commit(transactions.get(tid));
      }
    }
    committedTransactions.add(tid);
  }

  /**
   * Let the site at given index fail. Abort all transactions that have
   * accessed that site immediately.
   * @param index
   */
  private void fail(int siteIndex) {
    List<Integer> accessedTransactions = 
        databaseManagers.get(siteIndex).getAccessedTransaction();
    for (Integer tid : accessedTransactions) {
      abortedTransactions.add(tid);
    }
    databaseManagers.get(siteIndex).setStatus(false);
  }

  /**
   * Recovery site at given index.
   * @param index
   */
  private void recover(int index) {
    databaseManagers.get(index).recover();
  }

  /** Return all sites that storing given variable. */
  private List<Integer> getSites (int varIndex) {
    return variableMap.get(varIndex);
  }
  
  private boolean hasAborted(int tid) {
    return abortedTransactions.contains(tid);
  }

  /** Parse transaction id from "T*" string */
  private int parseTransactionId(String s) {
    return Integer.parseInt(s.substring(1));
  }

  /** Parse site index out of string. */
  private int parseSiteIndex(String s) {
    return Integer.parseInt(s);
  }
  
  /** Parse variable index from "x*" */
  private int parseVariable(String s) {
    return Integer.parseInt(s.substring(1));
  }
  
  /** Parse "T*, x*" into corresponding read operation */
  private Operation parseReadOperation(String arg) {
    String[] args = arg.split(",");
    check(args.length == 2, "Unexpected Read " + arg);
    int tid = parseTransactionId(args[0]);
    int var = parseVariable(args[1]);
    return new Operation(tid, var, timestamp, Operation.Type.READ);
  }

  /** Parse "T*, x*, **" into corresponding write operation */
  private Operation parseWriteOperation(String arg) {
    String[] args = arg.split(",");
    check(args.length == 3, "Unexpected Write " + arg);
    int tid = parseTransactionId(args[0]);
    int var = parseVariable(args[1]);
    int writeValue = Integer.parseInt(args[2]);
    return new Operation(tid, var, timestamp, Operation.Type.WRITE, writeValue);
  }
  
  /** If condition is false, print out error message and exit program */
  private void check(boolean condition, String errMsg) {
    if ( ! condition ) {
      System.err.println(errMsg);
      System.exit(-2);
    }
  }

}
