package li.cil.sedna.cli;

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

	public static void main(final String[] args) throws Exception {
		Sedna.initialize();

		final List<String> argList = Arrays.asList(args);
		final boolean enableGdbStub = argList.contains("-s");
		final boolean waitForGdb = argList.contains("-S");

		runSimple(enableGdbStub, waitForGdb);
	}

	private static void runSimple(final boolean enableGdbStub, final boolean waitForGdb) throws Exception {
		final R5Board board = new R5Board();
		final PhysicalMemory memory = Memory.create(VM_MEMORY_BYTES);
		final GoldfishRTC rtc = new GoldfishRTC(SystemTimeRealTimeCounter.get());

		final GlobalVMContext context = new GlobalVMContext(board);
		final BuiltinDevices builtinDevices;

		// grab minux images
		final Images images = getImages();

		// mount bootfs for first block device (vda)
		//   can we add this to context?
		final BlockDevice bootfs = ByteBufferBlockDevice.createFromStream(images.bootfs(), true);
		final VirtIOBlockDevice vda = new VirtIOBlockDevice(board.getMemoryMap(), bootfs);
		vda.getInterrupt().set(0x1, board.getInterruptController());
		board.addDevice(vda);

		// builtin device initialization. adds rootfs
		builtinDevices = new BuiltinDevices(context);

		// add a third device (vdc), from an image file.
		final BlockDevice vdc_fs = ByteBufferBlockDevice.createFromFile(GetImageFile(), false);
		final VirtIOBlockDevice vdc = new VirtIOBlockDevice(board.getMemoryMap(), vdc_fs);
		vdc.getInterrupt().set(0x3, board.getInterruptController());
		board.addDevice(vdc);

		// device adapters
		final RPCDeviceBusAdapter rpcAdapter = new RPCDeviceBusAdapter(builtinDevices.rpcSerialDevice);
		final VMDeviceBusAdapter vmAdapter;
		vmAdapter = new VMDeviceBusAdapter(context);

		// terminal signals
		SignalHandler handler = new SignalHandler () {
			// ^C
			public void handle(Signal sig) {
				builtinDevices.uart.putByte((byte) 0x03);
			}
		};

		Signal.handle(new Signal("INT"), handler);


		builtinDevices.uart.getInterrupt().set(0xA, board.getInterruptController());
		rtc.getInterrupt().set(0xB, board.getInterruptController());


		board.addDevice(0x80000000L, memory);
		board.addDevice(builtinDevices.uart);
		board.addDevice(rtc);

		board.getCpu().setFrequency(VM_CPU_FREQUENCY);
		board.setBootArguments("root=/dev/vda rw");
		board.setStandardOutputDevice(builtinDevices.uart);

		board.reset();

		// Add device firmware.
		loadProgramFile(memory, images.firmware());
		loadProgramFile(memory, images.kernel(), 0x200000);

		board.initialize();

		// Mount adapter devices.
		vmAdapter.mountDevices();
		rpcAdapter.mountDevices();

		board.setRunning(true);

		if (enableGdbStub) {
			board.enableGDB(55554, waitForGdb);
		}

		final int cyclesPerSecond = board.getCpu().getFrequency();
		final int cyclesPerStep = 1_000;

		try (final InputStreamReader isr = new InputStreamReader(System.in)) {
			final BufferedReader br = new BufferedReader(isr);

			int remaining = 0;
			while (board.isRunning()) {
				final long stepStart = System.currentTimeMillis();

				remaining += cyclesPerSecond;
				while (remaining > 0) {
					board.step(cyclesPerStep);
					rpcAdapter.step(cyclesPerStep);
					remaining -= cyclesPerStep;

					int value;
					while ((value = builtinDevices.uart.read()) != -1) {
						System.out.print((char) value);
					}

					while (br.ready() && builtinDevices.uart.canPutByte()) {
						builtinDevices.uart.putByte((byte) br.read());
					}
				}

				if (board.isRestarting()) {
					loadProgramFile(memory, images.firmware());
					loadProgramFile(memory, images.kernel(), 0x200000);

					board.initialize();
				}

				final long stepDuration = System.currentTimeMillis() - stepStart;
				// minimum ~1/60th second per loop
				final long sleep = 17 - stepDuration;
				if (sleep > 0) {
					//noinspection BusyWait
					Thread.sleep(sleep);
				}
			}

		// UnMount adapter devices.
		vmAdapter.unmountDevices();
		rpcAdapter.unmountDevices();
		}
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
