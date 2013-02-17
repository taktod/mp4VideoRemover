package com.ttProject.mp4;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.List;

public class VideoRemover {
	public static enum Type {
		Audio,
		Video
	};
	private FileChannel source;
	private FileChannel target;
	private long targetMoovPos;
	private long targetMdatPos;
	private List<Trak> trakList = new ArrayList<Trak>();
	/**
	 * @param args
	 */
	public static void main(String[] args) throws Exception {
		long start = System.currentTimeMillis();
		VideoRemover instance = new VideoRemover();
		try {
			// 内容を解析する。
			instance.analize();
		}
		catch (Exception e) {
			e.printStackTrace();
		}
		finally {
			instance.close();
		}
		System.out.println((System.currentTimeMillis() - start));
	}
	public VideoRemover() throws Exception {
		// 入力ソース
		source = new FileInputStream("test.mp4").getChannel();
		target = new FileOutputStream("output.mp4").getChannel();
	}
	public void analize() throws Exception {
		String tag = copyUntilTargetTag("mdat", "moov");
		targetMoovPos = target.position();
		System.out.println("みつけたタグ:" + tag);
		if(tag.equals("mdat")) {
			throw new RuntimeException("先にmdatが見つかりました、動作できません。");
		}
		copy("moov", true);
		System.out.println("moovの内部スタート位置:" + Long.toHexString(source.position()));
		// 各タグをみつけます
		// trakもしくはmdatまで普通にコピーします。
		tag = copyUntilTargetTag("trak", "mdat");
		System.out.println("trak探したい:" + tag);
		analizeTrak();
		// ここまでで、moovのサイズがきまっている。
		int sizeOfMoov = (int)(target.position() - targetMoovPos);
		targetMdatPos = target.position();
		target.position(targetMoovPos);
		ByteBuffer buffer = ByteBuffer.allocate(4);
		buffer.putInt(sizeOfMoov);
		buffer.flip();
		target.write(buffer);
		target.position(targetMdatPos);
		// ここまでで準備完了。
		copy("mdat", true);
		analizeBody();
		int sizeOfMdat = (int)(target.position() - targetMdatPos);
		target.position(targetMdatPos);
		buffer = ByteBuffer.allocate(4);
		buffer.putInt(sizeOfMdat);
		buffer.flip();
		target.write(buffer);
	}
	/**
	 * 実動画データを確認していく。
	 * @throws Exception
	 */
	private void analizeBody() throws Exception {
		// trakのデータから一番わかいものをみつける。
		Trak current = null;
		Trak targetTrak;
		do {
			for(Trak t : trakList) {
				if(current == null || current.getInfo() == -1 || current.getInfo() > t.getInfo()) {
					current = t;
				}
			}
			targetTrak = current;
			long startPos = current.getInfo();
			current.getNextInfo();
			current = null;
			for(Trak t : trakList) {
				if(current == null || current.getInfo() == -1 || current.getInfo() > t.getInfo()) {
					current = t;
				}
			}
			long endPos = current.getInfo();
			if(endPos == -1) {
				endPos = source.size();
			}
			if(targetTrak.getType() == Type.Audio) {
				// このファイルのデータをtargetに書き込んでaudio側のstcoに位置情報を書き込んでいく。
				long targetPos = target.position();
				// 時間について書き込みをすすめておく。
				targetTrak.updateTimePos((int)targetPos);
				target.position(targetPos);
				ByteBuffer buffer = ByteBuffer.allocate((int)(endPos - startPos));
				source.position(startPos);
				source.read(buffer);
				buffer.flip();
				target.write(buffer);
			}
			if(current.getInfo() == -1) {
				break;
			}
		}
		while(true);
	}
	/**
	 * trak要素の中身を調べる。
	 */
	private void analizeTrak() throws Exception {
		String tag = "trak";
		do {
			if("trak".equals(tag)) {
				Trak trak = new Trak();
				if(trak.getType() == Type.Video) {
					target.position(trak.getPosition());
				}
				trakList.add(trak);
			}
			else {
				copy(tag, true);
			}
			tag = copyUntilTargetTag("mdat", "trak", "mvex", "auth", "titl", "dscp", "cprt", "udta");
		}
		while(!"mdat".equals(tag));
	}
	/**
	 * Trakの内容保持
	 * とりあえず、source上のstcoの位置をしっておきたいのと、音声の場合はきちんと書き込めるようにしておきたい。
	 * @author taktod
	 */
	private class Trak {
		// 開始位置保持
		private long targetPosition;
		public long getPosition() {
			return targetPosition;
		}
		// 大本データのstcoの開始位置
		private long sourceStcoPos;
		private long targetStcoPos;
		private Type type;
		public Type getType() {
			return type;
		}
		private int dataCount;
		private long lastInfo = -1; // 最終アクセスデータ位置情報
		public Trak() throws Exception {
			type = null;
			// どういうタイプのtrakであるか確認しつつコピーしていく必要あり。
			targetPosition = target.position();
			copy("trak", true);
			copy("mdia", true);
			copy("minf", true);
			copy("stbl", true);
			copy("stco", false);
			// stcoの場所を保持しておく必要あり。
			init();
			System.out.println("Trak:targetPosition:" + Long.toHexString(targetPosition));
			System.out.println("Trak:sourceStcoPos:" + Long.toHexString(sourceStcoPos));
			System.out.println("Trak:targetStcoPos:" + Long.toHexString(targetStcoPos));
			System.out.println("Trak:dataCount:" + Long.toHexString(dataCount));
			System.out.println("Trak:type:" + type);
		}
		/**
		 * 
		 * @param tag
		 * @param sub
		 * @return
		 * @throws Exception
		 */
		private long copy(String tag, boolean sub) throws Exception {
			System.out.println("copyするよ？");
			int length = 0;
			long pos = 0;
			byte[] data = new byte[4];
			String findTag;
			ByteBuffer buffer;
			while(true) {
				pos = source.position();
				buffer = ByteBuffer.allocate(8);
				source.read(buffer);
				buffer.flip();
				length = buffer.getInt();
				buffer.get(data);
				findTag = new String(data);
				if(type == null) {
					if("smhd".equals(findTag)) {
						type = Type.Audio;
					}
					if("vmhd".equals(findTag)) {
						type = Type.Video;
					}
				}
				if(findTag.equals(tag)) {
					// sub(下のタグに移動する場合と振り分ける)
					// trueなら下のタグに移動したい。
					long targetPos = target.position();
					if(!sub) {
						// 元の場所に戻します。
						source.position(pos);
						sourceStcoPos = source.position();
						targetStcoPos = target.position();
						// コピーをつづけていきます。
						while(true) {
							if(length > 65536) {
								buffer = ByteBuffer.allocate(65536);
								source.read(buffer);
								buffer.flip();
								target.write(buffer);
								length -= 65536;
							}
							else {
								buffer = ByteBuffer.allocate(length);
								source.read(buffer);
								buffer.flip();
								target.write(buffer);
								length = 0;
								break;
							}
						}
					}
					else {
						buffer = ByteBuffer.allocate(8);
						buffer.putInt(length);
						buffer.put(findTag.getBytes());
						buffer.flip();
						target.write(buffer);
					}
					return targetPos;
				}
				else {
					// 問題のタグではなかったので、動作はスキップします。
					// 元の場所に戻します。
					source.position(pos);
					// コピーをつづけていきます。
					while(true) {
						if(length > 65536) {
							buffer = ByteBuffer.allocate(65536);
							source.read(buffer);
							// 他のタグだった場合
							buffer.flip();
							target.write(buffer);
							length -= 65536;
						}
						else {
							buffer = ByteBuffer.allocate(length);
							source.read(buffer);
							// 他のタグだった場合
							buffer.flip();
							target.write(buffer);
							length = 0;
							break;
						}
					}
				}
			}
		}
		/**
		 * 初期動作しておく。
		 */
		private void init() throws Exception {
			long initialPos = source.position();
			// 4バイト(サイズ)4バイト(stco)4バイト(0埋め)4バイト(配列量)
			if(type == Type.Audio) {
				targetStcoPos += 16; // 書き込み先のstcoPosは16バイトすすめておく。
			}
			// 読み込み用のデータ数を保持する。(source側の調整だけでOKみたい。)
			source.position(sourceStcoPos);
			ByteBuffer buffer = ByteBuffer.allocate(16);
			source.read(buffer);
			buffer.flip();
			buffer.getInt(); // データサイズ(興味なし)
			buffer.getInt(); // stcoの文字列(興味なし)
			buffer.getInt(); // 0固定値
			dataCount = buffer.getInt(); // 要素数
			sourceStcoPos += 16;
			source.position(initialPos);
		}
		/**
		 * データ位置情報を取得する。
		 * @return
		 */
		public long getInfo() throws Exception {
			if(lastInfo == -1) {
				getNextInfo();
			}
			return lastInfo;
		}
		public long getNextInfo() throws Exception {
			if(dataCount == 0) {
				lastInfo = -1;
			}
			else {
				ByteBuffer buffer = ByteBuffer.allocate(4);
				source.position(sourceStcoPos);
				source.read(buffer);
				sourceStcoPos += 4;
				dataCount --;
				buffer.flip();
				lastInfo = buffer.getInt();
			}
			return lastInfo;
		}
		public void updateTimePos(int position) throws Exception {
			if(type == Type.Audio) {
				target.position(targetStcoPos); // stcoのある位置
				ByteBuffer buffer = ByteBuffer.allocate(4);
				buffer.putInt(position);
				buffer.flip();
				target.write(buffer);
				targetStcoPos += 4;
			}
		}
	}
	/**
	 * 
	 * @param tags
	 * @return
	 * @throws Exception
	 */
	private String copyUntilTargetTag(String... tags) throws Exception {
		int length = 0;
		long pos = 0;
		byte[] data = new byte[4];
		String findTag;
		ByteBuffer buffer;
		while(true) {
			pos = source.position();
			buffer = ByteBuffer.allocate(8);
			source.read(buffer);
			buffer.flip();
			length = buffer.getInt();
			buffer.get(data);
			findTag = new String(data);
			boolean find = false;
			for(String tagName : tags) {
				if(findTag.equals(tagName)) {
					find = true;
					break;
				}
			}
			if(find) {
				// 元の場所に戻します。
				source.position(pos);
				return findTag; // 見つけたタグを応答します。(これが次のタグ)
			}
			else {
				// 問題のタグではなかったので、動作はスキップします。
				// 元の場所に戻します。
				source.position(pos);
				// コピーをつづけていきます。
				while(true) {
					if(length > 65536) {
						buffer = ByteBuffer.allocate(65536);
						source.read(buffer);
						buffer.flip();
						target.write(buffer);
						length -= 65536;
					}
					else {
						buffer = ByteBuffer.allocate(length);
						source.read(buffer);
						buffer.flip();
						target.write(buffer);
						length = 0;
						break;
					}
				}
			}
		}
	}
	/**
	 * 
	 * @param tag
	 * @param sub
	 * @return
	 * @throws Exception
	 */
	private long copy(String tag, boolean sub) throws Exception {
		int length = 0;
		long pos = 0;
		byte[] data = new byte[4];
		String findTag;
		ByteBuffer buffer;
		while(true) {
			pos = source.position();
			buffer = ByteBuffer.allocate(8);
			source.read(buffer);
			buffer.flip();
			length = buffer.getInt();
			buffer.get(data);
			findTag = new String(data);
			if(findTag.equals(tag)) {
				// sub(下のタグに移動する場合と振り分ける)
				// trueなら下のタグに移動したい。
				long targetPos = target.position();
				if(!sub) {
					// 元の場所に戻します。
					source.position(pos);
					// コピーをつづけていきます。
					while(true) {
						if(length > 65536) {
							buffer = ByteBuffer.allocate(65536);
							source.read(buffer);
							buffer.flip();
							target.write(buffer);
							length -= 65536;
						}
						else {
							buffer = ByteBuffer.allocate(length);
							source.read(buffer);
							buffer.flip();
							target.write(buffer);
							length = 0;
							break;
						}
					}
				}
				else {
					buffer = ByteBuffer.allocate(8);
					buffer.putInt(length);
					buffer.put(findTag.getBytes());
					buffer.flip();
					target.write(buffer);
				}
				return targetPos;
			}
			else {
				// 問題のタグではなかったので、動作はスキップします。
				// 元の場所に戻します。
				source.position(pos);
				// コピーをつづけていきます。
				while(true) {
					if(length > 65536) {
						buffer = ByteBuffer.allocate(65536);
						source.read(buffer);
						buffer.flip();
						target.write(buffer);
						length -= 65536;
					}
					else {
						buffer = ByteBuffer.allocate(length);
						source.read(buffer);
						buffer.flip();
						target.write(buffer);
						length = 0;
						break;
					}
				}
			}
		}
	}
	/**
	 * 
	 * @throws Exception
	 */
	public void close() throws Exception {
		source.close();
		target.close();
	}
}
