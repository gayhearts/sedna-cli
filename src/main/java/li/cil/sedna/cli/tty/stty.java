package li.cil.sedna.cli.tty;

import java.lang.Runtime;
import java.io.Reader;
import java.io.InputStream;

public class stty {
   public String attributes_default = null;
   public String attributes         = null;
   public String attributes_store   = null;
   public boolean initialized       = false;
   
   public stty() {
      try {
	 this.attributes_default = this.exec("-g");
	 this.attributes         = this.attributes_default;
	 this.attributes_store   = this.attributes_default;
	 this.initialized        = true;
      } catch (Throwable thrown) {
	 System.out.printf("%s\n", thrown.toString());
      }
   }
   
   protected void Load() {
      this.attributes = this.attributes_store;
      this.exec(this.attributes);
   }
   
   protected void Store() {
      this.attributes_store = this.attributes;
   }
   
   public void LoadDefault() {
      this.attributes = this.attributes_default;
      this.exec(this.attributes);
   }
   
   protected String exec(String arg) {
      StringBuilder printout = new StringBuilder();
      
      String[] cmd;
      Process stty_proc;
      
      try {
	 cmd = new String[] {"/usr/bin/env", "sh", "-c", "stty " + arg + " </dev/tty"};
	 stty_proc = Runtime.getRuntime().exec(cmd);
      
	 if( stty_proc.waitFor() == 0 ){
	    InputStream in = stty_proc.getInputStream();
	    int value;
	    
	    while((value = in.read()) != -1 && value != 0x0A) {
	       printout.append((char) value);
	    }
	      
	      if( printout.length() > 0 ){
		 return printout.toString();
	      }
	 }
      } catch (Throwable thrown) {
	 System.out.printf("%s\n", thrown.toString());
      }
      
      return null;
   }
   
   public void Close() {
      this.exec(this.attributes_default);
      this.attributes_default = null;
      this.attributes         = null;
      this.attributes_store   = null;
      this.initialized        = false;
   }
}