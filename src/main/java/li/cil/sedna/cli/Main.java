package li.cil.sedna.cli;

import li.cil.sedna.cli.tty.*;

import li.cil.sedna.Sedna;
import li.cil.sedna.api.Sizes;
import li.cil.sedna.api.device.BlockDevice;
import li.cil.sedna.api.device.PhysicalMemory;
import li.cil.sedna.buildroot.Buildroot;
import li.cil.sedna.device.block.ByteBufferBlockDevice;
import li.cil.sedna.device.memory.Memory;
import li.cil.sedna.device.rtc.GoldfishRTC;
import li.cil.sedna.device.rtc.SystemTimeRealTimeCounter;
import li.cil.sedna.device.serial.UART16550A;
import li.cil.sedna.device.virtio.VirtIOBlockDevice;
import li.cil.sedna.device.virtio.VirtIOConsoleDevice;
import li.cil.sedna.device.virtio.VirtIOFileSystemDevice;
import li.cil.sedna.fs.HostFileSystem;
import li.cil.sedna.riscv.R5Board;
import li.cil.sedna.riscv.R5CPU;
import org.jline.terminal.Size;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.terminal.impl.DumbTerminal;
import org.jline.utils.NonBlockingReader;

import li.cil.oc2.api.bus.device.Device;
import li.cil.oc2.api.bus.device.object.Callback;
import li.cil.oc2.api.bus.device.object.NamedDevice;
import li.cil.oc2.api.bus.device.object.ObjectDevice;
import li.cil.oc2.api.bus.device.rpc.RPCDevice;

import com.google.common.eventbus.EventBus;
import com.google.common.eventbus.SubscriberExceptionContext;

import li.cil.oc2.common.bus.RPCDeviceBusAdapter;
import li.cil.oc2.common.vm.context.global.GlobalVMContext;
import li.cil.oc2.common.vm.BuiltinDevices;
import li.cil.oc2.common.vm.VMDeviceBusAdapter;



import sun.misc.Signal;
import sun.misc.SignalHandler;

import java.io.*;
import java.util.Arrays;
import java.util.List;

public final class Main {
	public static int VM_MEMORY_MEGABYTES = 32;
	public static int VM_MEMORY_BYTES = (VM_MEMORY_MEGABYTES * 1024 * 1024);

	public static int VM_CPU_FREQUENCY = 25_000_000;

        public static terminal term;
   
	public static void main(final String[] args) throws Exception {
		Sedna.initialize();

		final List<String> argList = Arrays.asList(args);
		final boolean enableGdbStub = argList.contains("-s");
		final boolean waitForGdb = argList.contains("-S");

	        term = new terminal();
	   
	        if( term == null ){
		   return;
		}
	   
	        term.SetRaw(true);
	   
		RunSedna(enableGdbStub, waitForGdb);
	   
	        term.Close();
	}

	private static void RunSedna(final boolean enableGdbStub, final boolean waitForGdb) throws Exception {
		final R5Board board = new R5Board();
		final PhysicalMemory memory = Memory.create(VM_MEMORY_BYTES);
		final GoldfishRTC rtc = new GoldfishRTC(SystemTimeRealTimeCounter.get());
		final UART16550A uart = new UART16550A();

		// grab minux images
		final Images images = getImages();

		/// MINUX BLOCK DEVICES
		// BootFS block device.
		final BlockDevice bootfs = ByteBufferBlockDevice.createFromStream(images.bootfs(), true);
		final VirtIOBlockDevice vda = new VirtIOBlockDevice(board.getMemoryMap(), bootfs);
		vda.getInterrupt().set(0x5, board.getInterruptController());
		board.addDevice(vda);

		// RootFS block device.
		final BlockDevice rootfs = ByteBufferBlockDevice.createFromStream(images.rootfs(), true);
		final VirtIOBlockDevice vdb = new VirtIOBlockDevice(board.getMemoryMap(), rootfs);
		vdb.getInterrupt().set(0x6, board.getInterruptController());
		board.addDevice(vdb);

		// Add a third device (vdc), from an image file.
		final BlockDevice vdc_fs = ByteBufferBlockDevice.createFromFile(GetImageFile(), false);
		final VirtIOBlockDevice vdc = new VirtIOBlockDevice(board.getMemoryMap(), vdc_fs);
		vdc.getInterrupt().set(0x7, board.getInterruptController());
		board.addDevice(vdc);


		// terminal signals
		SignalHandler handler = new SignalHandler () {
			// ^C
			public void handle(Signal sig) {
				uart.putByte((byte) 0x03);
			}
		};

		Signal.handle(new Signal("INT"), handler);



		// RPC bus.
		final VirtIOConsoleDevice rpcSerialDevice = new VirtIOConsoleDevice(board.getMemoryMap());
		final RPCDeviceBusAdapter rpcAdapter = new RPCDeviceBusAdapter(rpcSerialDevice);
		rpcSerialDevice.getInterrupt().set(0x3, board.getInterruptController());
		board.addDevice(rpcSerialDevice);


		uart.getInterrupt().set(0xA, board.getInterruptController());
		rtc.getInterrupt().set(0xB, board.getInterruptController());


		board.addDevice(0x80000000L, memory);
		board.addDevice(uart);
		board.addDevice(rtc);

		board.getCpu().setFrequency(VM_CPU_FREQUENCY);
		board.setBootArguments("root=/dev/vda rw");
		board.setStandardOutputDevice(uart);

		board.reset();

		// Add device firmware.
		loadProgramFile(memory, images.firmware());
		loadProgramFile(memory, images.kernel(), 0x200000);

		board.initialize();

		// Mount adapter devices.
		rpcAdapter.mountDevices();

		board.setRunning(true);

		if (enableGdbStub) {
			board.enableGDB(55554, waitForGdb);
		}

		final int cyclesPerSecond = board.getCpu().getFrequency();
		final int cyclesPerMillis = (cyclesPerSecond / 1_000);
		final int cyclesPerStep = 1_000;

			int remaining = 0;
			while (board.isRunning()) {
				final long step_end = System.currentTimeMillis() + 1;

				// Is this required?
				// Need to re-figure out the RPC Adapter.
				rpcAdapter.tick();

				remaining += cyclesPerMillis;
				while (remaining > 0) {
					board.step(cyclesPerStep);
					rpcAdapter.step(cyclesPerStep);

					remaining -= cyclesPerStep;

					int value;
					while ((value = uart.read()) != -1) {
						System.out.print((char) value);
					}

					while (term.stdin.ready() && uart.canPutByte()) {
						uart.putByte((byte) term.stdin.read());
					}
				}

				if (board.isRestarting()) {
					loadProgramFile(memory, images.firmware());
					loadProgramFile(memory, images.kernel(), 0x200000);

					board.initialize();
				}

				while (System.currentTimeMillis() < step_end) {
					//noinspection BusyWait
					Thread.sleep(0, 25);
				}
			}

		// UnMount adapter devices.
		rpcAdapter.unmountDevices();
		
	}

	private static void loadProgramFile(final PhysicalMemory memory, final InputStream stream) throws Exception {
		loadProgramFile(memory, stream, 0);
	}

	private static void loadProgramFile(final PhysicalMemory memory, final InputStream stream, final int offset) throws IOException {
		final BufferedInputStream bis = new BufferedInputStream(stream);
		for (int address = offset, value = bis.read(); value != -1; value = bis.read(), address++) {
			memory.store(address, (byte) value, Sizes.SIZE_8_LOG2);
		}
	}

	// Currently hard-coded. minux.img at project root.
	private static File GetImageFile() throws IOException {
		return new File("../../minux.img");
	}

	private static Images getImages() throws IOException {
		return new Images(
				Buildroot.getFirmware(),
				Buildroot.getLinuxImage(),
				Buildroot.getBootFilesystem(),
				Buildroot.getRootFilesystem());
	}

	private record Images(InputStream firmware, InputStream kernel, InputStream bootfs, InputStream rootfs) { }
}
