package li.cil.sedna.cli;

import li.cil.sedna.cli.tty.terminal;
import li.cil.sedna.cli.tty.stty;

import li.cil.sedna.riscv.R5Board;

public class MetaShell {
   private static String interpret(terminal term, R5Board board, String command) {
      switch(command) {
       case "quit":
               board.setRunning(false);
               return "quit";
       case "exit":
               return "exit";
       case "dump":
	 System.out.printf("%s\r\n", term.Dump());
       default:
               System.out.printf("\r\nCommand \"%s\" not found.\r\n", command);
      }
      return "0";
   }

   private void cleanup(terminal term) {
      term.Load();
   }

   public static String Shell(terminal term, R5Board board) {
      String input_line   = null;
      String return_value = "0";
      System.out.printf("\r\nEntering MetaShell.\r\n");
      
      term.Store();
      term.SetRaw(false);
      
      try {
	 while( (input_line = System.console().readLine("; ")) != null ){
	    return_value = interpret(term, board, input_line);
	    
	    if( return_value != "0" ) {
	       break;
	    }
	 }
	    
      } catch (Throwable thrown) {
	 System.out.printf("%s\n", thrown.toString());
	 return_value = null;
      }
	 
      term.Load();
      return return_value;
   }
   
   public static void HandleEscape(terminal term, R5Board board) {
      try {
	 final int input = term.stdin.read();

	 switch (input) {
	  case 0x43:
	  case 0x63:
	  case 0x03:
	    MetaShell.Shell(term, board);
	    break;
	  case (int) 'p':
	    System.out.printf("%s\r\n", term.Dump());
	 break;
	 }
      } catch (Throwable ignored) {}
   }
}