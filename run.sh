#!/usr/bin/env sh

set -o posix

## if using yash, uncomment to make more posix. 
#set -o posixly-correct

IsTrapped() {
	if( test $# -ne 4 ); then
		return 3
	else
		signal_value=$(echo "$3" | tr -d \')

		if( test "$signal_value" == "true" ); then
			return 0 #true
		elif( test "$signal_value" == "-" ); then
			return 1 #false
		else
			return 4 #unknown
		fi
	fi
}

## general settings
export JAVA_OPTS="-Xms4096M -Xmx6144M"

## store parent terminal info
PARENT_TTY_SETTINGS="$(stty -g)"
PARENT_RUNNING_DIR="$(pwd)"

## Get script/git directory.
SELF_CMD="$0"
SELF_PATH="$(realpath "$SELF_CMD")"
SELF_DIR="$(dirname "$SELF_PATH")"
SELF_NAME="$(basename "$SELF_PATH")"

## Get project info.
IFS= command eval 'GRADLE_PROPERTIES=$("$SELF_DIR/gradlew" --quiet properties)'
GRADLE_VERSION="$(echo "$GRADLE_PROPERTIES" | sed -n "s/version: //p")"
GRADLE_NAME="$(echo "$GRADLE_PROPERTIES" | sed -n "s/name: //p")"

if (test -z "$GRADLE_VERSION"); then
	echo "Unable to retrieve project version."
	exit 1
elif (test -z "$GRADLE_NAME"); then
	echo "Unable to retrieve project name."
	exit 2
else
	GRADLE_BUILD_STRING="$GRADLE_NAME-$GRADLE_VERSION"
fi

## Check parent data is there.
##   if not, we can't safely return from vm.
if (test -z "$PARENT_TTY_SETTINGS"); then
	echo "NO TTY SETTINGS FOUND."
	exit 3
elif (test -z "$PARENT_RUNNING_DIR"); then
	echo "NO PARENT TERMINAL RUNNING DIRECTORY FOUND."
	exit 4
else
	# Trap ^C
	trap true SIGINT

	## check trap succeeded
	trap_sigint=$(trap -p SIGINT)
	if (! IsTrapped $trap_sigint); then
		echo "Unable to trap ^C."
		echo "	exiting."
		exit 5
	fi

	build_tarball="$SELF_DIR/build/distributions/$GRADLE_BUILD_STRING.tar"
	if (test ! -e "$build_tarball"); then
		echo "$GRADLE_BUILD_STRING tarball not found."
		echo "Did you forget to build project?"
		exit 6
	else
		vm_distros_directory="$SELF_DIR/vm_distributions"
		mkdir -p "$vm_distros_directory"
		
		tar xf "$build_tarball" -C "$vm_distros_directory"

		if (test ! -d "$vm_distros_directory/$GRADLE_BUILD_STRING"); then
			echo "VM Distribution Destination not found."
			echo "Likely an error extracting, or the packing of the distrobution's tarball."
			exit 7
		else
			# Change to our VM Distro's directory.
			cd "$vm_distros_directory/$GRADLE_BUILD_STRING"
		fi
	fi

	## Now actually run Sedna.
	# term settings
	stty -echo raw
	stty -icanon min 1

	./bin/sedna-cli

	## Restore terminal settings
	stty "$PARENT_TTY_SETTINGS"
	cd "$PARENT_RUNNING_DIR"
fi
