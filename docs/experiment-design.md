#### Question and x axis
*define the dimension you sweep. Examples are* `maxRowsPerPartition` *from 2 to 1024, table size from 1K to 1M rows, sorted versus shuffled input, or predicate selectivity from 0% to 100%.*

We want to test how partition size (`maxRowsPerPartition`, which is defaulted to 1.000) affects the duration of `SELECT` statements.
\
The experiment will be run with partition sizes of 2, 3, 10, 25, 100, 500, 1.000, 2.500, 5.000, and 10.000. Partition size are the x axis.
\
**TABLE SIZE TBD**

#### Metric and y axis 
*define what you measure from your own log. This could be the fraction of partitions read from the* `decision=` *lines, or the median* `durationMs` *from the summary lines. State the plot before you see it: what is on the x axis, what is on the y axis, and what one curve represents. Use a log scale on x if the values span multiple orders of magnitude.*

The median of a `SELECT` statement's `durationMs` will constitute the y axis. These are read from our logs.

#### Procedure
*describe data generation, the values you vary, the number of repetitions, whether you include or exclude the first cold run, the machine, JVM version, and heap size.*

The test data will be a fixed data set, so that all experiment runs will use the same data, just with varying partition sizes. We will create a table each time, then run a cold run (which will be excluded from the results). Each experiment will be run 5 times, whereafter we will calculate the average.
\
JVM Version is 26. Machine specs will be noted in the reports (we will try both machines and decide which is preferable).

#### Hypothesis
*state it before the first run, and quantify it where possible. "Sorted input halves the partitions read at every partition size" is informative. "Pruning improves" is less informative.*

Our expectation is, that the experiment results will form a bell curve. We expect that the experiments with smaller partition sizes will be slower due to a higher throughput, as we will have to check many tiny partitions. When the partition sizes grow larger, we expect the performance to improve, up until a certain point, where the partitions are so large that pruning stops. 