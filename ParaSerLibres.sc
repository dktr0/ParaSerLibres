FragmentCollector {

	var expected;
	var <text;

	init {
		expected=0;
		text="";
	}

	cojer { |n,count,textFragment|
		if(n!=expected,{
			// "ERROR: fragment out of sequence".postln;
			expected = 0;
			text = "";
			^false;
		},
		{
			text = if(n==0,textFragment,text++textFragment);
			if(n<(count-1),{
				expected = expected + 1;
				^false;
			},
			{
				expected = 0;
				^true;
			});
		});
	}
}


ParaSerLibres {

	// public class variables
	classvar <>fftBuffer;
	classvar <>nodePath;
	classvar <>server;
	classvar >password;
	classvar <mainBus;

	// private variables
	classvar netAddr;
	classvar editCollector;
	classvar evalCollector;
	classvar mainBusObject;
	classvar firstRead;

	*initClass {
		firstRead = false;
		editCollector = FragmentCollector.new;
		evalCollector = FragmentCollector.new;
		nodePath = "node";
	}

	*node {
		|cmd|
		fork {
			"pkill -f cosechar.js".systemCmd;
			0.3.wait;
			"pkill -f sembrar.js".systemCmd;
			0.3.wait;
			cmd = "cd " ++ Platform.userExtensionDir.escapeChar($ ) ++ "/ParaSerLibres && " ++ nodePath ++ " " ++ cmd;
			cmd.runInTerminal;
		}
	}

	*nodeSembrar {
		if(server.isNil || password.isNil,{
			"ERROR: Either or both of ParaSerLibres.server and/or ParaSerLibres.password have not been set".postln;
		},{
			this.node("sembrar.js ws://" ++ server ++ " " ++ password);
		});
	}

	*nodeCosechar {
		if(server.isNil,{
			"ERROR: ParaSerLibres.server has not been set".postln;
		},{
			this.node("cosechar.js ws://" ++ server);
		});
	}

	*synths {
		var path;
		mainBusObject = Bus.audio(Server.default,8);
		mainBus = mainBusObject.index;
		path = (Platform.userExtensionDir ++ "/ParaSerLibres/Synths.scd").standardizePath;
		thisProcess.interpreter.executeFile(path);
		Server.default.freeAll;
		if(Server.default.options.numOutputBusChannels >= 10, {
			SynthDef(\out,{
				var multi = In.ar(mainBus,8)*(-9.dbamp);
				var stereo = Splay.ar(multi)*(-3.dbamp);
				multi = Compander.ar(multi,multi,thresh:-10.dbamp,slopeAbove:1/20);
				stereo = Compander.ar(stereo,stereo,thresh:-10.dbamp,slopeAbove:1/10);
				Out.ar(0,stereo);
				Out.ar(2,multi);
			}).play(addAction:\addToTail);
			"ParaSerLibres 10-channel config: 2 channels stereo output + 8 channels main output".postln;
		});
		if(Server.default.options.numOutputBusChannels < 10, {
			SynthDef(\out,{
				var multi = In.ar(mainBus,8)*(-9.dbamp);
				var stereo = Splay.ar(multi)*(-3.dbamp);
				stereo = Compander.ar(stereo,stereo,thresh:-10.dbamp,slopeAbove:1/10);
				Out.ar(0,stereo);
			}).play(addAction:\addToTail);
			"ParaSerLibres 2-channel config: stereo mix of 8 channels only".postln;
		});
		if(fftBuffer.notNil,{
			this.fftSynth;
		});
	}

	*pdefs {
		var path = (Platform.userExtensionDir ++ "/ParaSerLibres/Pdefs.scd").standardizePath;
		var file = File.new(path,"r");
		Document.current.text = file.readAllString;
		Document.current.text.asString.interpret;
	}

	*sembrar { |reinit=false|
		this.synths;
		this.nodeSembrar;
		netAddr = NetAddr.new("127.0.0.1",8000);
		if(reinit,{
			var y;
			this.pdefs;
			y = Document.current.string.clump(500);
			y.collect({|z,i| netAddr.sendMsg("/edit",i,y.size,z,NetAddr.langPort);});
			this.sembrarHooks;
		},{
			OSCdef(\sembrar,{ |m,t,a,p|
				if(editCollector.cojer(m[1],m[2],m[3]),{
					Tdef(\sembrar).stop;
					if(Document.current.notNil,{Document.current.text = editCollector.text});
					editCollector.text.interpret.postln;
					OSCdef(\sembrar).free;
					this.sembrarHooks;
				});
			},"/sembrar").permanent_(true);
			Tdef(\sembrar,{
				inf.do { netAddr.sendMsg("/sembrar",NetAddr.langPort); 0.25.wait; };
			}).play;
		});
	}

	*risembrar {
		netAddr.sendMsg("/sembrar",NetAddr.langPort);
	}

	*sembrarHooks {
		Document.current.keyUpAction = {
			var y = Document.current.string.clump(500);
			y.collect({|z,i| netAddr.sendMsg("/edit",i,y.size,z,NetAddr.langPort);});
			netAddr.sendMsg("/cursor",Document.current.selectionStart,NetAddr.langPort);
		};
		thisProcess.interpreter.codeDump = { |x|
			var y = x.clump(500);
			y.collect({|z,i| netAddr.sendMsg("/eval",i,y.size,z,NetAddr.langPort);});
		};
	}

	*cosechar {
		var y;
		this.nodeCosechar;
		firstRead = true;
		this.synths;
		this.pdefs;
		netAddr = NetAddr.new("127.0.0.1",8001);

		OSCdef(\edit,{ |m,t,a,p|
			if(editCollector.cojer(m[1],m[2],m[3]),{
				if(Document.current.notNil,{ Document.current.text = editCollector.text });
				if(firstRead,{
					firstRead = false;
					Tdef(\cosecharInit).stop;
					editCollector.text.interpret.postln;
				});
			});
		},"/edit").permanent_(true);

		OSCdef(\eval,{ |m,t,a,p|
			if(evalCollector.cojer(m[1],m[2],m[3]),{
				evalCollector.text.interpret.postln;
			});
		},"/eval").permanent_(true);

		OSCdef(\cursor,{ |m,t,a,p|
			if(Document.current.notNil,{ Document.current.selectRange(m[1]) });
		},"/eval").permanent_(true);

		Tdef(\cosecharInit,{
			inf.do { netAddr.sendMsg("/read",NetAddr.langPort); 0.25.wait; }
		}).play;

		SkipJack.new( {
			netAddr.sendMsg("/read",NetAddr.langPort);
		},5, clock: SystemClock);

	}

	*fftSynth {
		SynthDef("fft", {
			var stereo, multi, suma, chain, ampL, ampR;
			stereo = In.ar(0,2);
			multi = In.ar(2,8);
			suma = A2K.kr(Amplitude.ar(multi.sum/8,0.005, 0.05));
			chain = FFT(fftBuffer.bufnum, multi.sum/8, wintype: 1);
			ampL = A2K.kr(Amplitude.ar(stereo[0], 0.005, 0.05));
			ampR = A2K.kr(Amplitude.ar(stereo[1], 0.005, 0.05));
			SendReply.kr(Impulse.kr(30),'/amp',values:[ampL,ampR]);
		}).play(addAction:\addToTail);
	}
}
