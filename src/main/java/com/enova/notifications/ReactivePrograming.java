package com.enova.notifications;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.time.Duration;

public class ReactivePrograming {


    /**
     * Mono : Représente une séquence de zéro ou une seule émission
     */

    public static void mono(){
        Mono<String> mono = Mono.just("Hello, Reactor!");
        mono.subscribe(System.out::println); // Hello, Reactor!

        Mono<String> empty = Mono.empty(); // completes without emitting
        Mono<String> error = Mono.error(new RuntimeException("Oops")); // terminates with error
    }

    /**
     * Flux : Représente une séquence de zéro ou plusieurs émissions
     */


    public static void flux(){
        Flux<Integer> flux = Flux.just(1, 2, 3, 4, 5);
        flux.subscribe(System.out::println); // prints 1 2 3 4 5

        Flux<Integer> range = Flux.range(1, 10); // emits 1 to 10
        Flux<Long>  interval = Flux.interval(Duration.ofSeconds(1)); // emits every second

//        range.subscribe(System.out::println); // prints 1 to 10
        interval.subscribe(System.out::println); // prints 1 to 10
    }


    public static void lifeCycleHooks(){
        Flux.range(1, 10)
                .doOnSubscribe(sub->System.out.println("onSubscribe"))
                .doOnNext(val->System.out.println("onNext: " + val))
                .doOnComplete(()->System.out.println("onComplete"))
                .doOnError(err->System.out.println("onError: " + err))
                .doFinally(signal->System.out.println("onFinally: " + signal))
                .subscribe();
    }

    /**
     * Sinks One : un seul abonné
     */
    public static void SinksOne(){
        Sinks.One<String> sink = Sinks.one();

        // Récupérer le Mono côté abonnés
        Mono<String> mono = sink.asMono();
        mono.subscribe(val -> System.out.println("Reçu: " + val));

        // Émettre depuis n'importe où (autre thread, événement externe...)
        sink.tryEmitValue("Hello!");     // Reçu: Hello!
        sink.tryEmitValue("Hicham!");     // Reçu: Hello!

        // Ou signaler une erreur
        sink.tryEmitError(new RuntimeException("Échec"));

        // Ou compléter sans valeur (Mono.empty())
        sink.tryEmitEmpty();

        mono.subscribe(n->System.out.println(n));
    }


    /**
     * Sinks unicast : un seul abonné
     */


    public static void unicastSinks() {
        Sinks.Many<Integer> sink = Sinks.many().unicast().onBackpressureBuffer();

        Flux<Integer> flux = sink.asFlux();
        flux.subscribe(n -> System.out.println("Sub: " + n));
        
        sink.tryEmitNext(1);  // Sub: 1
        sink.tryEmitNext(2);  // Sub: 2
        sink.tryEmitNext(3);  // Sub: 3
        sink.tryEmitComplete();

// Tenter un 2ème abonné → IllegalStateException !
        flux.subscribe(); // ERREUR
    }



    public static void main(String[] args) {
//        unicastSinks();
//        SinksOne();
//        mono();
//        flux();

//        Flux<Integer> flux = Flux.create(sink->{
//            sink.next(1);
//            sink.next(2);
//            sink.next(3);
//            sink.complete();
//        });
//        flux.subscribe(System.out::println);
        lifeCycleHooks();


    }


}
