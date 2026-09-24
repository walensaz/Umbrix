package umbrix.core.session

import java.io.File
import java.nio.file.Paths
import munit.FunSuite
import umbrix.core.types.LineIndex
import umbrix.index.{PhysicalLineIndexerImpl, TemporalCheckpointTable, TwoLevelSparseIndex}
import umbrix.vfs.MemoryMappedLogReader

class HadoopHdfsTemporalTest extends FunSuite {

  test("diagnose hadoop-hdfs-namenode-mesos-01.log temporal indexing") {
    val path = Paths.get("C:\\Users\\zwale\\Downloads\\Thunderbird\\hadoop-hdfs-namenode-mesos-01.log")
    if (java.nio.file.Files.exists(path)) {
      println(s"File exists! Size: ${java.nio.file.Files.size(path)} bytes")
      val storageReader = MemoryMappedLogReader.open(path)
      try {
        println(s"Building TwoLevelSparseIndex...")
        val sparseIndex = TwoLevelSparseIndex.build(storageReader)
        val totalLines = sparseIndex.totalLines
        println(s"Total lines: ${totalLines.value}")

        val indexer = new PhysicalLineIndexerImpl(storageReader, sparseIndex)
        val line0Slice = storageReader.readSlice(0, 120)
        println(s"Line 0 content: ${new String(line0Slice.toArray(java.lang.foreign.ValueLayout.JAVA_BYTE))}")
        println(s"Building TemporalCheckpointTable...")
        val table = TemporalCheckpointTable.build(indexer)
        println(s"Checkpoint count: ${table.checkpointCount}")
        println(s"First timestamp: ${table.firstTimestamp}")
        println(s"Last timestamp: ${table.lastTimestamp}")

        if (table.checkpointCount > 0) {
          for (i <- 0 until math.min(10, table.checkpointCount)) {
            println(s"  Checkpoint $i: line ${table.lineAtCheckpoint(i).value} -> ${table.timestampAtCheckpoint(i).value}")
          }
          val lastIdx = table.checkpointCount - 1
          println(s"  Last Checkpoint $lastIdx: line ${table.lineAtCheckpoint(lastIdx).value} -> ${table.timestampAtCheckpoint(lastIdx).value}")

          println("Timestamp profile across file:")
          for (i <- 0 until table.checkpointCount by 100) {
            println(s"  cp $i (line ${table.lineAtCheckpoint(i).value}): ${table.timestampAtCheckpoint(i).value}")
          }
          println(s"  cp ${table.checkpointCount - 1} (line ${table.lineAtCheckpoint(table.checkpointCount - 1).value}): ${table.timestampAtCheckpoint(table.checkpointCount - 1).value}")
        }

        val minT = table.firstTimestamp.get
        val maxT = table.lastTimestamp.get
        println(s"table.firstTimestamp: ${minT.value} (${java.time.Instant.ofEpochMilli(minT.value / 1000)})")
        println(s"table.lastTimestamp: ${maxT.value} (${java.time.Instant.ofEpochMilli(maxT.value / 1000)})")

        val (r0Start, r0End) = table.seekLineRange(minT, maxT)
        println(s"Direct table.seekLineRange [min, max]: startLine=${r0Start.value}, endLine=${r0End.value} (count=${r0End.value - r0Start.value + 1})")

        val midT = umbrix.core.types.EpochMicros(minT.value + (maxT.value - minT.value) / 2)
        val (r1Start, r1End) = table.seekLineRange(minT, midT)
        println(s"Direct table.seekLineRange [min, mid]: startLine=${r1Start.value}, endLine=${r1End.value} (count=${r1End.value - r1Start.value + 1})")

        val (r2Start, r2End) = table.seekLineRange(midT, maxT)
        println(s"Direct table.seekLineRange [mid, max]: startLine=${r2Start.value}, endLine=${r2End.value} (count=${r2End.value - r2Start.value + 1})")
      } finally {
        storageReader.close()
      }
    } else {
      println("File does not exist on disk, skipping.")
    }
  }
}
