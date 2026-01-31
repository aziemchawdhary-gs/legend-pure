#!/bin/bash
#
# Script to run PureCompiledJarRunner outside of Maven for heap/metaspace investigation.
#
# Usage:
#   ./run-pure-generator.sh --repositories <repos> --jar-roots <dirs> [options]
#
# Required:
#   --repositories <repos>   Comma-separated list of repositories to include (e.g., "platform")
#   --jar-roots <dirs>       Colon-separated list of directories to scan for jars
#
# Examples:
#   ./run-pure-generator.sh --repositories platform --jar-roots "/path/to/legend-pure:/path/to/legend-engine"
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
    echo "  --classpath-file <file>  Write classpath to this file (default: auto-generated temp file)"
    echo "  --keep-classpath-file    Don't delete the classpath file on exit"
    echo "  --help                   Show this help"
    echo "  [other options]          Passed through to PureCompiledJarRunner"
    echo ""
    echo "Environment variables:"
    echo "  JVM_OPTS                 JVM options (default: -Xmx4g -XX:MaxMetaspaceSize=512m)"
    echo ""
    echo "Examples:"
    echo "  $0 --repositories platform --jar-roots ~/.m2/repository/org/finos/legend"
    echo "  $0 --repositories platform --jar-roots \"/path/to/legend-pure:/path/to/legend-engine\""
    echo "  $0 --repositories platform --jar-roots /path/to/repo --include-classes"
    echo "  $0 --repositories platform --jar-roots /path/to/repo --classpath-file /tmp/my-cp.txt --keep-classpath-file"
}

# Parse arguments
REPOSITORIES=""
JAR_ROOTS=""
INCLUDE_CLASSES=false
CLASSPATH_FILE=""
KEEP_CLASSPATH_FILE=false
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
        --classpath-file)
            i=$((i + 1))
            CLASSPATH_FILE="${!i}"
            ;;
        --keep-classpath-file)
            KEEP_CLASSPATH_FILE=true
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

# Set up classpath file
if [ -z "$CLASSPATH_FILE" ]; then
    CLASSPATH_FILE=$(mktemp /tmp/pure-generator-classpath.XXXXXX)
    if [ "$KEEP_CLASSPATH_FILE" = false ]; then
        trap "rm -f '$CLASSPATH_FILE'" EXIT
    fi
fi

echo "============================================================"
echo "Pure Generator Runner"
echo "============================================================"
echo "Repositories:     $REPOSITORIES"
echo "Jar roots:        $JAR_ROOTS"
echo "Include classes:  $INCLUDE_CLASSES"
echo "Classpath file:   $CLASSPATH_FILE"
echo "============================================================"
echo ""

# Build the classpath file
echo "Building classpath..."
build_classpath_file "$JAR_ROOTS" "$CLASSPATH_FILE" "$INCLUDE_CLASSES"
CP_COUNT=$(count_classpath_file_entries "$CLASSPATH_FILE")
echo "Classpath entries: $CP_COUNT"

if [ "$CP_COUNT" -eq 0 ]; then
    echo "Error: No jars found in specified roots"
    exit 1
fi

# Convert to colon-separated string for java -cp
JAVA_CLASSPATH=$(file_to_classpath_string "$CLASSPATH_FILE")

# For the --classpath argument to PureCompiledJarRunner, we'll pass the file path
# and let it read from the file. But since the runner expects a colon-separated string,
# we need to pass the string. For very long classpaths, we'll just accept the limitation
# for now or the runner could be modified to accept a file.

# Default JVM options
DEFAULT_JVM_OPTS="-Xmx4g -XX:MaxMetaspaceSize=512m"
JVM_OPTS="${JVM_OPTS:-$DEFAULT_JVM_OPTS}"

# Default output directories
DEFAULT_CLASSES_OUTPUT="/tmp/pure-generator-output/classes"
DEFAULT_TARGET_OUTPUT="/tmp/pure-generator-output"

# Check if user provided output directories
HAS_CLASSES_DIR=false
HAS_TARGET_DIR=false

for arg in "${EXTRA_ARGS[@]}"; do
    case "$arg" in
        --classesDirectory) HAS_CLASSES_DIR=true ;;
        --targetDirectory) HAS_TARGET_DIR=true ;;
    esac
done

# Build command arguments
CMD_ARGS=()
CMD_ARGS+=("--repositories" "$REPOSITORIES")
CMD_ARGS+=("--classpath-file" "$CLASSPATH_FILE")

if [ "$HAS_CLASSES_DIR" = false ]; then
    mkdir -p "$DEFAULT_CLASSES_OUTPUT"
    CMD_ARGS+=("--classesDirectory" "$DEFAULT_CLASSES_OUTPUT")
    echo "Using default classes output: $DEFAULT_CLASSES_OUTPUT"
fi

if [ "$HAS_TARGET_DIR" = false ]; then
    mkdir -p "$DEFAULT_TARGET_OUTPUT"
    CMD_ARGS+=("--targetDirectory" "$DEFAULT_TARGET_OUTPUT")
    echo "Using default target output: $DEFAULT_TARGET_OUTPUT"
fi

CMD_ARGS+=("${EXTRA_ARGS[@]}")

echo ""
echo "JVM options: $JVM_OPTS"
if [ "$KEEP_CLASSPATH_FILE" = true ]; then
    echo "Classpath file will be kept: $CLASSPATH_FILE"
fi
echo "============================================================"
echo ""

# Run the generator
exec java $JVM_OPTS \
    -cp "$JAVA_CLASSPATH" \
    org.finos.legend.pure.runtime.java.compiled.generation.orchestrator.PureCompiledJarRunner \
    "${CMD_ARGS[@]}"
