package li.cil.sedna.cli.tty;

import java.io.InputStreamReader;
import java.io.BufferedReader;


public class terminal {
   public  stty              tty;
   public  BufferedReader    stdin;
   private InputStreamReader input_stream;

   public boolean raw  = false;
   public boolean echo = true;
   
   public terminal() {
      this.tty = new stty();
      
      try {
	 this.input_stream = new InputStreamReader(System.in);
	 this.stdin        = new BufferedReader(this.input_stream);
      } catch (Throwable thrown) {
	 System.out.printf("%s\n", thrown.toString());
	 return;
      }
   }

   public void SetEcho(boolean value) {
      if (value == true){
	 this.tty.exec("echo");
      } else if (value == false) {
	 this.tty.exec("-echo");
      }
      
      this.echo = value;
   }
   
   public void SetRaw(boolean value) {
      if (value == true) {
	 this.tty.exec("-echo raw");
	 this.tty.exec("-icanon min 1");
      } else if (value == false) {
	 this.tty.exec("echo -raw");
	 this.tty.exec("icanon");
      }
      
      this.raw  = value;
      this.echo = !value;
   }

   public void Load() {
      this.tty.Load();
   }
   
   public void Store() {
      this.tty.Store();
   }
   
   public String Dump() {
      return this.tty.attributes;
   }
   
   public void Close() {
      this.tty.Close();
   }
}
