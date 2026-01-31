#!/bin/bash
#
# Script to run PureCompiledJarRunner with full diagnostic options enabled.
# Use this for investigating heap/metaspace issues.
#
# Usage:
#   ./run-pure-generator-diagnostic.sh --repositories <repos> --jar-roots <dirs> [options]
#
# Required:
#   --repositories <repos>   Comma-separated list of repositories to include (e.g., "platform")
#   --jar-roots <dirs>       Colon-separated list of directories to scan for jars
#
# Environment variables:
#   JAVA_HOME      - Java installation to use (optional)
#   JVM_HEAP       - Max heap size (default: 4g)
#   JVM_METASPACE  - Max metaspace size (default: 512m)
#   ITERATIONS     - Number of iterations to run (default: 1)
#   HEAP_DUMP_DIR  - Directory for heap dumps (default: /tmp/pure-diagnostics)
#

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MODULE_DIR="$(cd "$SCRIPT_DIR/../../.." && pwd)"

#
# Function to find all jar files under a directory
# Usage: find_jars_to_file <directory> <output_file>
# Appends jar paths to the output file, one per line
#
find_jars_to_file() {
    local dir="$1"
    local output_file="$2"
    if [ ! -d "$dir" ]; then
        echo "Warning: Directory not found: $dir" >&2
        return
    fi
    find "$dir" -name "*.jar" -type f 2>/dev/null >> "$output_file"
}

#
# Function to find all target/classes directories under a directory
# Usage: find_classes_dirs_to_file <directory> <output_file>
# Appends classes directory paths to the output file, one per line
#
find_classes_dirs_to_file() {
    local dir="$1"
    local output_file="$2"
    if [ ! -d "$dir" ]; then
        echo "Warning: Directory not found: $dir" >&2
        return
    fi
    find "$dir" -type d -name "classes" -path "*/target/classes" 2>/dev/null >> "$output_file"
}

#
# Function to build classpath file from multiple root directories
# Usage: build_classpath_file <colon-separated-roots> <output_file> [include-classes]
# Creates a file with one classpath entry per line
#
build_classpath_file() {
    local roots="$1"
    local output_file="$2"
    local include_classes="${3:-false}"

    # Clear/create the output file
    > "$output_file"

    # Split roots by colon
    IFS=':' read -ra ROOT_ARRAY <<< "$roots"

    for root in "${ROOT_ARRAY[@]}"; do
        if [ -z "$root" ]; then
            continue
        fi

        # Expand ~ if present
        root="${root/#\~/$HOME}"

        if [ ! -d "$root" ]; then
            echo "Warning: Root directory not found: $root" >&2
            continue
        fi

        echo "Scanning for jars in: $root" >&2

        # Add jars
        find_jars_to_file "$root" "$output_file"

        # Optionally add classes directories
        if [ "$include_classes" = "true" ]; then
            find_classes_dirs_to_file "$root" "$output_file"
        fi
    done
}

#
# Function to convert classpath file (one entry per line) to colon-separated string
#
file_to_classpath_string() {
    local file="$1"
    # Read file, filter empty lines, join with colons
    grep -v '^$' "$file" 2>/dev/null | paste -sd ':' -
}

#
# Function to count entries in a classpath file
#
count_classpath_file_entries() {
    local file="$1"
    grep -v '^$' "$file" 2>/dev/null | wc -l
}

print_usage() {
    echo "Usage: $0 --repositories <repos> --jar-roots <dirs> [options]"
    echo ""
    echo "Required:"
    echo "  --repositories <repos>   Comma-separated list of repositories (e.g., \"platform\")"
    echo "  --jar-roots <dirs>       Colon-separated list of directories to scan for jars"
    echo ""
    echo "Optional:"
    echo "  --include-classes        Also include target/classes directories in classpath"
    echo "  --help                   Show this help"
    echo "  [other options]          Passed through to PureCompiledJarRunner"
    echo ""
    echo "Environment variables:"
    echo "  JAVA_HOME                Java installation to use"
    echo "  JVM_HEAP                 Max heap size (default: 4g)"
    echo "  JVM_METASPACE            Max metaspace size (default: 512m)"
    echo "  ITERATIONS               Number of iterations (default: 1)"
    echo "  HEAP_DUMP_DIR            Output directory (default: /tmp/pure-diagnostics)"
    echo ""
    echo "Examples:"
    echo "  $0 --repositories platform --jar-roots ~/.m2/repository/org/finos/legend"
    echo "  $0 --repositories platform --jar-roots \"/path/to/legend-pure:/path/to/legend-engine\""
    echo "  ITERATIONS=5 $0 --repositories platform --jar-roots /path/to/repo --include-classes"
}

# Parse arguments
REPOSITORIES=""
JAR_ROOTS=""
INCLUDE_CLASSES=false
EXTRA_ARGS=()

i=1
while [ $i -le $# ]; do
    arg="${!i}"
    case "$arg" in
        --repositories)
            i=$((i + 1))
            REPOSITORIES="${!i}"
            ;;
        --jar-roots)
            i=$((i + 1))
            JAR_ROOTS="${!i}"
            ;;
        --include-classes)
            INCLUDE_CLASSES=true
            ;;
        --help|-h)
            print_usage
            exit 0
            ;;
        *)
            EXTRA_ARGS+=("$arg")
            ;;
    esac
    i=$((i + 1))
done

# Validate required arguments
if [ -z "$REPOSITORIES" ]; then
    echo "Error: --repositories is required"
    echo ""
    print_usage
    exit 1
fi

if [ -z "$JAR_ROOTS" ]; then
    echo "Error: --jar-roots is required"
    echo ""
    print_usage
    exit 1
fi

# Configuration with defaults
JVM_HEAP="${JVM_HEAP:-4g}"
JVM_METASPACE="${JVM_METASPACE:-512m}"
ITERATIONS="${ITERATIONS:-1}"
HEAP_DUMP_DIR="${HEAP_DUMP_DIR:-/tmp/pure-diagnostics}"
TIMESTAMP=$(date +%Y%m%d-%H%M%S)

# Create output directories
OUTPUT_DIR="$HEAP_DUMP_DIR/$TIMESTAMP"
mkdir -p "$OUTPUT_DIR/classes"
mkdir -p "$OUTPUT_DIR/logs"

# Classpath file location
CLASSPATH_FILE="$OUTPUT_DIR/classpath.txt"

echo "============================================================"
echo "Pure Generator Diagnostic Runner"
echo "============================================================"
echo "Timestamp:        $TIMESTAMP"
echo "Output directory: $OUTPUT_DIR"
echo "Repositories:     $REPOSITORIES"
echo "Jar roots:        $JAR_ROOTS"
echo "Include classes:  $INCLUDE_CLASSES"
echo "Heap size:        $JVM_HEAP"
echo "Metaspace:        $JVM_METASPACE"
echo "Iterations:       $ITERATIONS"
echo "============================================================"
echo ""

# Build the classpath file
echo "Building classpath..."
build_classpath_file "$JAR_ROOTS" "$CLASSPATH_FILE" "$INCLUDE_CLASSES"
CP_COUNT=$(count_classpath_file_entries "$CLASSPATH_FILE")
echo "Classpath entries: $CP_COUNT (saved to $CLASSPATH_FILE)"

if [ "$CP_COUNT" -eq 0 ]; then
    echo "Error: No jars found in specified roots"
    exit 1
fi

# Convert to colon-separated string for java -cp
JAVA_CLASSPATH=$(file_to_classpath_string "$CLASSPATH_FILE")

# Determine Java version for appropriate flags
JAVA_CMD="${JAVA_HOME:+$JAVA_HOME/bin/}java"
JAVA_VERSION=$($JAVA_CMD -version 2>&1 | head -1 | cut -d'"' -f2 | cut -d'.' -f1)

echo "Java command: $JAVA_CMD"
echo "Java major version: $JAVA_VERSION"

# Build JVM diagnostic flags based on Java version
JVM_OPTS="-Xmx$JVM_HEAP -XX:MaxMetaspaceSize=$JVM_METASPACE"

# Heap dump on OOM
JVM_OPTS="$JVM_OPTS -XX:+HeapDumpOnOutOfMemoryError"
JVM_OPTS="$JVM_OPTS -XX:HeapDumpPath=$OUTPUT_DIR/oom-heapdump.hprof"

# GC logging
if [ "$JAVA_VERSION" -ge 9 ] 2>/dev/null; then
    # JDK 9+ unified logging
    JVM_OPTS="$JVM_OPTS -Xlog:gc*=info:file=$OUTPUT_DIR/logs/gc.log:time,uptime,level,tags"
    JVM_OPTS="$JVM_OPTS -Xlog:class+load=info:file=$OUTPUT_DIR/logs/classload.log:time,uptime"
    JVM_OPTS="$JVM_OPTS -Xlog:class+unload=info:file=$OUTPUT_DIR/logs/classunload.log:time,uptime"
    JVM_OPTS="$JVM_OPTS -Xlog:gc+metaspace=info:file=$OUTPUT_DIR/logs/metaspace.log:time,uptime"
else
    # JDK 8 flags
    JVM_OPTS="$JVM_OPTS -XX:+PrintGCDetails -XX:+PrintGCDateStamps"
    JVM_OPTS="$JVM_OPTS -Xloggc:$OUTPUT_DIR/logs/gc.log"
    JVM_OPTS="$JVM_OPTS -verbose:class"
fi

# Native memory tracking (useful for metaspace analysis)
JVM_OPTS="$JVM_OPTS -XX:NativeMemoryTracking=summary"

# Save configuration for reference
cat > "$OUTPUT_DIR/config.txt" << EOF
Timestamp: $TIMESTAMP
Java: $JAVA_CMD
Java Version: $JAVA_VERSION
Repositories: $REPOSITORIES
Jar Roots: $JAR_ROOTS
Include Classes: $INCLUDE_CLASSES
Heap: $JVM_HEAP
Metaspace: $JVM_METASPACE
Iterations: $ITERATIONS
Classpath Entries: $CP_COUNT
Classpath File: $CLASSPATH_FILE
JVM Options: $JVM_OPTS
EOF

echo ""
echo "JVM Options:"
echo "$JVM_OPTS" | tr ' ' '\n' | grep -v '^$' | sed 's/^/  /'
echo ""
echo "Logs will be written to: $OUTPUT_DIR/logs/"
echo ""

# Build runner arguments
RUNNER_ARGS=(
    "--repositories" "$REPOSITORIES"
    "--classpath-file" "$CLASSPATH_FILE"
    "--classesDirectory" "$OUTPUT_DIR/classes"
    "--targetDirectory" "$OUTPUT_DIR"
    "--iterations" "$ITERATIONS"
    "--gcBetweenIterations"
    "--heapDumpOnExit"
    "--heapDumpPath" "$OUTPUT_DIR/final-heapdump.hprof"
)

# Add any additional arguments passed to this script
RUNNER_ARGS+=("${EXTRA_ARGS[@]}")

echo "Runner arguments:"
printf '  %s\n' "${RUNNER_ARGS[@]}"
echo ""
echo "============================================================"
echo "Starting Pure Generator..."
echo "============================================================"
echo ""

# Capture start time
START_TIME=$(date +%s)

# Run the generator, capturing output to both console and log file
$JAVA_CMD $JVM_OPTS \
    -cp "$JAVA_CLASSPATH" \
    org.finos.legend.pure.runtime.java.compiled.generation.orchestrator.PureCompiledJarRunner \
    "${RUNNER_ARGS[@]}" 2>&1 | tee "$OUTPUT_DIR/logs/runner.log"

EXIT_CODE=${PIPESTATUS[0]}
END_TIME=$(date +%s)
DURATION=$((END_TIME - START_TIME))

echo ""
echo "============================================================"
echo "Completed in ${DURATION}s with exit code $EXIT_CODE"
echo "============================================================"
echo ""
echo "Output files:"
ls -lh "$OUTPUT_DIR"/*.hprof 2>/dev/null || echo "  (no heap dumps)"
echo ""
echo "Log files:"
ls -lh "$OUTPUT_DIR/logs/"
echo ""
echo "Classpath file saved at: $CLASSPATH_FILE"
echo ""
echo "To analyze the heap dump:"
echo "  jhat $OUTPUT_DIR/final-heapdump.hprof"
echo "  # or use VisualVM, Eclipse MAT, etc."
echo ""
echo "To view native memory summary:"
echo "  jcmd <pid> VM.native_memory summary"
echo ""

exit $EXIT_CODE
