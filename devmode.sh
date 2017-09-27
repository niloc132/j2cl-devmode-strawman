#!/usr/bin/env bash
set -e

# Update these env vars as needed
J2CL_REPO=/Users/colin/workspace/j2cl
CLOSURE_LIBRARY_REPO=/Users/colin/workspace/closure-library
CLOSURE_COMPILER_JAR=/Users/colin/workspace/closure-compiler/target/closure-compiler-1.0-SNAPSHOT.jar

# Finally, skip down to the end of the file to customize what dependencies you build, compile.


base_cp="$J2CL_REPO/bazel-bin/jre/java/jre.jar"


sources=${1-.}
pom=${2-pom.xml}
serve_from=${3-out}
j2cl_cp=$base_cp
timestamp=$sources/.timestamp


# Call j2cl directly
J2clTranspiler() {
  $J2CL_REPO/bazel-bin/transpiler/java/com/google/j2cl/transpiler/J2clTranspiler "$@"
}

# An apparently deprecated tool, first cut toward a "dev mode" sort of env. Currently this output is unused,
# but it is important to note that building and including it into the jszip doesn't negatively impact jscomp.
depswriter() {
 $CLOSURE_LIBRARY_REPO/closure/bin/build/depswriter.py "$@"
}

echo_and_run() { echo "$@" ; "$@" ; }

compile_changed() {
    # if marker file doesn't exist, die
    if [[ ! -e "$timestamp" ]]
    then
        echo "$timestamp doesn't exist, please relaunch"
        exit -1;
    fi

    modified_list=$(find $sources -type f -newer $timestamp -name '*.java' | tr '\n' ' ')

    touch $timestamp

    # if empty, sleep a second and return
    if [[ -z $modified_list ]]
    then
        sleep 1
    else

        # else recompile all files
        # TODO on error, nuke the output dir, so we can start over from scratch
        echo_and_run J2clTranspiler -cp $j2cl_cp -d $serve_from/project $modified_list && echo "Recompile Successful"
        depswriter --root $serve_from/project > $serve_from/project/deps.js

    fi
}

echo_script() {
    echo "<script src=\"$1\"></script>"
}

# setup: verify pid file doesn't exist, or someone else is doing this
if [ -e "$timestamp" ]
then
  echo "$timestamp exists, delete it or use the existing dev mode"
  exit -1;
fi
# set up a trap to delete the pid, then create the pid with date 0 to compile everything
trap "rm -f $timestamp; exit 1" 0 1 2 3 13 15
touch -t "197001010000" "$timestamp"

# collect, unpack dependencies and build classpath string
mkdir -p $serve_from/deps
mkdir -p $serve_from/project

mvn -f $pom dependency:build-classpath -Dmdep.outputFile=`pwd`/cp.txt
j2cl_cp=$j2cl_cp:`cat cp.txt`
rm cp.txt

mvn -f $pom dependency:build-classpath -Dmdep.outputFile=`pwd`/cp.txt -Dclassifier=jszip -Dtype=zip
zips=`cat cp.txt`

pushd $serve_from/deps
IFS=':'
for zip in $zips; do
    unzip -o $zip
done
popd
unset IFS

# soft link to closure-library
ln -sf $CLOSURE_LIBRARY_REPO/closure/goog $serve_from/deps/goog


# echo base.js and all deps.js files as script tags (TODO relative to what?)
# gather all deps.js files
echo_script deps/goog/base.js
echo_script project/deps.js
for dep in $(ls $serve_from/deps | grep "\-deps\.js$"); do
    echo_script deps/$dep
done;

# spin, looking for changed files and recompiling them
while true
do
    compile_changed
done