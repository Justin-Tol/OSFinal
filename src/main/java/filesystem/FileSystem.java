package filesystem;

import java.io.IOException;
import java.lang.*;
import java.util.ArrayList;
import java.util.List;


public class FileSystem {
    private Disk diskDevice;
    private int iNodeNumber;
    private int fileDescriptor;
    private INode iNodeForFile;

    public FileSystem() throws IOException {
        diskDevice = new Disk();
        diskDevice.format();
    }

    /***
     * Create a file with the name <code>fileName</code>
     *
     * @param fileName - name of the file to create
     * @throws IOException
     */
    public int create(String fileName) throws IOException {
        INode tmpINode = null;

        boolean isCreated = false;

        for (int i = 0; i < Disk.NUM_INODES && !isCreated; i++) {
            tmpINode = diskDevice.readInode(i);
            String name = tmpINode.getFileName();

            // The Fix: Add a null check before calling trim()
            if (name != null && name.trim().equals(fileName)) {
                throw new IOException("FileSystem::create: " + fileName + " already exists");
            } else if (tmpINode.getFileName() == null) { // No need for trim() here since we already checked for null
                this.iNodeForFile = new INode();
                this.iNodeForFile.setFileName(fileName);
                this.iNodeNumber = i;
                this.fileDescriptor = i;
                isCreated = true;
            }
        }
        if (!isCreated) {
            throw new IOException("FileSystem::create: Unable to create file");
        }

        return fileDescriptor;
    }

    /**
     * Removes the file
     *
     * @param fileName
     * @throws IOException
     */
    public void delete(String fileName) throws IOException {
        INode tmpINode = null;
        boolean isFound = false;
        int inodeNumForDeletion = -1;

        /**
         * Find the non-null named inode that matches,
         * If you find it, set its file name to null
         * to indicate it is unused
         */
        for (int i = 0; i < Disk.NUM_INODES && !isFound; i++) {
            tmpINode = diskDevice.readInode(i);

            String fName = tmpINode.getFileName();

            if (fName != null && fName.trim().compareTo(fileName.trim()) == 0) {
                isFound = true;
                inodeNumForDeletion = i;
                break;
            }
        }

        /***
         * If file found, go ahead and deallocate its
         * blocks and null out the filename.
         */
        if (isFound) {
            deallocateBlocksForFile(inodeNumForDeletion);
            tmpINode.setFileName(null);
            diskDevice.writeInode(tmpINode, inodeNumForDeletion);
            this.iNodeForFile = null;
            this.fileDescriptor = -1;
            this.iNodeNumber = -1;
        }
    }


    /***
     * Makes the file available for reading/writing
     *
     * @return
     * @throws IOException
     */
    public int open(String fileName) throws IOException {
        this.fileDescriptor = -1;
        this.iNodeNumber = -1;
        INode tmpINode = null;
        boolean isFound = false;
        int iNodeContainingName = -1;

        for (int i = 0; i < Disk.NUM_INODES && !isFound; i++) {
            tmpINode = diskDevice.readInode(i);
            String fName = tmpINode.getFileName();
            if (fName != null) {
                if (fName.trim().compareTo(fileName.trim()) == 0) {
                    isFound = true;
                    iNodeContainingName = i;
                    this.iNodeForFile = tmpINode;
                }
            }
        }

        if (isFound) {
            this.fileDescriptor = iNodeContainingName;
            this.iNodeNumber = fileDescriptor;
        }

        return this.fileDescriptor;
    }


    /***
     * Closes the file
     *
     * @throws IOException If disk is not accessible for writing
     */
    public void close(int fileDescriptor) throws IOException {
        if (fileDescriptor != this.iNodeNumber){
            throw new IOException("FileSystem::close: file descriptor, "+
                    fileDescriptor + " does not match file descriptor " +
                    "of open file");
        }
        diskDevice.writeInode(this.iNodeForFile, this.iNodeNumber);
        this.iNodeForFile = null;
        this.fileDescriptor = -1;
        this.iNodeNumber = -1;
    }


    /**
     * Reads file and outputs as a String
     *
     * @return
     * @param fileDescriptor
     * @throws IOException
     */
    public String read(int fileDescriptor) throws IOException {
        if (fileDescriptor != this.iNodeNumber || this.iNodeForFile == null) {
            throw new IOException("FileSystem::read: Invalid file descriptor or inode is null.");
        }

        INode inode = this.iNodeForFile;
        int fileSize = inode.getSize();
        byte[] fileData = new byte[fileSize];
        int bytesRead = 0;

        // Read from each allocated block
        for (int i = 0; i < INode.NUM_BLOCK_POINTERS && bytesRead < fileSize; i++) {
            int blockNumber = inode.getBlockPointer(i);
            if (blockNumber == -1) break;

            // Read the block data
            byte[] blockData = diskDevice.readDataBlock(blockNumber);

            // Calculate how many bytes to read from this block
            int bytesToRead = Math.min(Disk.BLOCK_SIZE, fileSize - bytesRead);

            // Copy from blockData to fileData
            System.arraycopy(blockData, 0, fileData, bytesRead, bytesToRead);
            bytesRead += bytesToRead;
        }

        return new String(fileData);
    }

    /**
     * Add your Javadoc documentation for this method
     */
    public void write(int fileDescriptor, String data) throws IOException {
        if (fileDescriptor != this.iNodeNumber || this.iNodeForFile == null) {
            throw new IOException("FileSystem::write: Invalid file descriptor or inode is null.");
        }

        byte[] dataBytes = data.getBytes();
        int requiredBlocks = (int) Math.ceil((double) dataBytes.length / Disk.BLOCK_SIZE);

        // First deallocate any existing blocks
        deallocateBlocksForFile(this.iNodeNumber);

        // Allocate new blocks
        int[] allocatedBlocks = allocateBlocksForFile(this.iNodeNumber, dataBytes.length);

        // Write data to blocks
        int bytesWritten = 0;
        for (int i = 0; i < allocatedBlocks.length && bytesWritten < dataBytes.length; i++) {
            byte[] blockData = new byte[Disk.BLOCK_SIZE]; // Create a full-size block
            int bytesToWrite = Math.min(Disk.BLOCK_SIZE, dataBytes.length - bytesWritten);

            // Copy the next chunk of data into the block
            System.arraycopy(dataBytes, bytesWritten, blockData, 0, bytesToWrite);

            // Write the block to disk
            diskDevice.writeDataBlock(blockData, allocatedBlocks[i]);

            // Update inode with block pointer
            this.iNodeForFile.setBlockPointer(i, allocatedBlocks[i]);

            bytesWritten += bytesToWrite;
        }

        // Update file size and save inode
        this.iNodeForFile.setSize(dataBytes.length);
        diskDevice.writeInode(this.iNodeForFile, this.iNodeNumber);
    }

    /**
     * Add your Javadoc documentation for this method
     */
    private int[] allocateBlocksForFile(int iNodeNumber, int numBytes) throws IOException {
        if (iNodeNumber < 0 || iNodeNumber >= Disk.NUM_INODES) {
            throw new IOException("FileSystem::allocateBlocksForFile: Invalid inode number.");
        }

        int requiredBlocks = (int) Math.ceil((double) numBytes / Disk.BLOCK_SIZE);
        if (requiredBlocks > INode.NUM_BLOCK_POINTERS) {
            throw new IOException("FileSystem::allocateBlocksForFile: File too large for available block pointers.");
        }

        byte[] freeBlockList = diskDevice.readFreeBlockList();
        List<Integer> allocatedBlocks = new ArrayList<>();

        // Find free blocks
        for (int block = 0; block < Disk.NUM_BLOCKS && allocatedBlocks.size() < requiredBlocks; block++) {
            int byteIndex = block / 8;
            int bitIndex = block % 8;
            int mask = 1 << bitIndex;

            // Check if block is free (bit is 0)
            if ((freeBlockList[byteIndex] & mask) == 0) {
                // Mark block as allocated
                freeBlockList[byteIndex] |= mask;
                allocatedBlocks.add(block);
            }
        }

        if (allocatedBlocks.size() < requiredBlocks) {
            throw new IOException("FileSystem::allocateBlocksForFile: Not enough free blocks.");
        }

        // Save the updated free block list
        diskDevice.writeFreeBlockList(freeBlockList);

        // Clear old block pointers and set new ones
        for (int i = 0; i < INode.NUM_BLOCK_POINTERS; i++) {
            if (i < allocatedBlocks.size()) {
                this.iNodeForFile.setBlockPointer(i, allocatedBlocks.get(i));
            } else {
                this.iNodeForFile.setBlockPointer(i, -1);
            }
        }

        return allocatedBlocks.stream().mapToInt(Integer::intValue).toArray();
    }

    /**
     * Add your Javadoc documentation for this method
     */
    private void deallocateBlocksForFile(int iNodeNumber) throws IOException {
        if (iNodeNumber < 0 || iNodeNumber >= Disk.NUM_INODES) {
            throw new IOException("FileSystem::deallocateBlocksForFile: Invalid inode number.");
        }

        // Read the inode from disk
        INode inode = diskDevice.readInode(iNodeNumber);
        if (inode == null) {
            throw new IOException("FileSystem::deallocateBlocksForFile: Inode is null.");
        }

        byte[] freeBlockList = diskDevice.readFreeBlockList();

        // Free all blocks used by the file
        for (int i = 0; i < INode.NUM_BLOCK_POINTERS; i++) {
            int blockNumber = inode.getBlockPointer(i);
            if (blockNumber == -1) break;

            // Clear the bit in free block list
            int byteIndex = blockNumber / 8;
            int bitIndex = blockNumber % 8;
            freeBlockList[byteIndex] &= ~(1 << bitIndex);

            // Clear the block pointer
            inode.setBlockPointer(i, -1);
        }

        // Update free block list and inode
        diskDevice.writeFreeBlockList(freeBlockList);
        diskDevice.writeInode(inode, iNodeNumber);
    }
}
