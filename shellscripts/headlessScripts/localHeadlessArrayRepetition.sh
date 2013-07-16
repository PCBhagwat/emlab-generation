#!/bin/bash

########################################################################
# The jobname must only consist of the characters A-Z, a-z and 0-9!!!! #
########################################################################
USAGE="Provide name of run and number of runs"
#Load configuration script to substitute
if [ -f scriptConfigurations.cfg ];then 
	. scriptConfigurations.cfg
	HOME=$REMOTERESULTFOLDER
else
    echo "Define scriptConfigurations.cfg, by changing the template. Exiting script."
    exit
fi

## the first parameter gives the jobname, the second the scenario-file excluding(!) the xml file-ending
## the scenario files should correspond to the range of number of PBS_ARRAYID.
## Example sh localHeadless.sh example 2 scenarioA
JOBNAME=$1
SCENARIO=$2
SCENARIOPATH=file://$LOCALSCENARIOFOLDER
#START=$3
#END=$4
REPETITION=$3

if [ ! -z $5 ] 
then 
#    QUERYCOMMAND="-Dquery.file=$5"
    QUERYCOMMAND="-Dquery.file=$4"
else
    QUERYCOMMAND=""
fi

mkdir $LOCALRESULTFOLDER/$JOBNAME
cd $LOCALRESULTFOLDER/$JOBNAME
#for PBS_ARRAYID in $(eval echo "{$START..$END}")
for rep in $(eval echo "{1..$REPETITION}")
do
#echo "$SCENARIO-$PBS_ARRAYID.xml"
echo "$SCENARIO.xml"
java -d64 -server -Xmx3072m -Drun.id=$JOBNAME-$PBS_ARRAYID -DSCENARIO_FOLDER=$SCENARIOPATH -Dresults.path=$LOCALRESULTFOLDER/$JOBNAME -Dscenario.file=$SCENARIO".xml" $QUERYCOMMAND -jar $LOCALJARFILE
#mv simulation.log "$JOBNAME-$PBS_ARRAYID.log"
mv simulation.log "$JOBNAME-$REPETITION.log"
rm -rf /tmp/ramdisk/emlab.gen-db/$JOBNAME-$REPETITION
done
#done
