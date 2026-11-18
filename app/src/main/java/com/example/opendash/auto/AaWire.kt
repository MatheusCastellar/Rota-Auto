package com.example.opendash.auto

import java.io.*

/** AAP framing, based on the public Open Headunit protocol definitions. AGPL-3.0. */
object AaWire {
    const val MAX_MESSAGE=4*1024*1024
    data class Frame(val channel:Int,val flags:Int,val total:Int,val payload:ByteArray)
    fun read(input:InputStream):Frame {
        val d=DataInputStream(input)
        val channel=d.readUnsignedByte(); val flags=d.readUnsignedByte(); val size=d.readUnsignedShort()
        require(channel in 0..15 && flags and 0xf0==0 && size>0) { "Cabeçalho Android Auto inválido" }
        val total=if(flags and 3==1) d.readInt() else 0
        require(total in 0..MAX_MESSAGE && (flags and 3!=1 || total>=2)) { "Tamanho Android Auto inválido" }
        return Frame(channel,flags,total,ByteArray(size).also { d.readFully(it) })
    }
    fun write(output:OutputStream,channel:Int,flags:Int,payload:ByteArray,total:Int=0) {
        require(payload.size in 1..65535)
        val d=DataOutputStream(output)
        d.writeByte(channel); d.writeByte(flags); d.writeShort(payload.size)
        if(flags and 3==1) d.writeInt(total)
        d.write(payload); d.flush()
    }
    class Assembler {
        private data class Partial(val size:Int,val control:Int,val out:ByteArrayOutputStream)
        private val parts=HashMap<Int,Partial>()
        fun accept(frame:Frame,plain:ByteArray):ByteArray? {
            val first=frame.flags and 1!=0; val last=frame.flags and 2!=0
            if(first && last) {
                check(!parts.containsKey(frame.channel)) { "Mensagem anterior incompleta" }
                require(plain.size in 2..MAX_MESSAGE); return plain
            }
            if(first) {
                check(!parts.containsKey(frame.channel)) { "Fragmentação interrompida" }
                require(frame.total in 2..MAX_MESSAGE)
                parts[frame.channel]=Partial(frame.total,frame.flags and 4,ByteArrayOutputStream())
            }
            val part=parts[frame.channel] ?: error("Fragmento sem início")
            require(frame.flags and 4==part.control && part.out.size()+plain.size<=part.size)
            part.out.write(plain)
            if(!last) return null
            require(part.out.size()==part.size) { "Mensagem truncada" }
            parts.remove(frame.channel); return part.out.toByteArray()
        }
    }
}
