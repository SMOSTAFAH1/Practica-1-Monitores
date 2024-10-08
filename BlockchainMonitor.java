package cc.blockchain;

import es.upm.babel.cclib.*;
import java.util.ArrayList;
import es.upm.aedlib.map.*;

/**
 * Clase BlockchainMonitor que implementa la interfaz Blockchain.
 * Esta clase se encarga de gestionar las transacciones en una blockchain.
 */
public class BlockchainMonitor implements Blockchain {

	// Monitor para controlar el acceso concurrente
	private volatile Monitor mutex = new Monitor();
	// Mapa de cuentas con su saldo
	private volatile Map<String, Integer> cuentas = new HashTableMap<>();
	// Mapa de identidades públicas con sus correspondientes identidades privadas
	private volatile Map<String, String> identidades = new HashTableMap<>();
	// Lista de peticiones de transferencia pendientes
	private volatile ArrayList<Peticion> peticiones = new ArrayList<>();
	// Lista de peticiones de alerta pendientes
	private volatile ArrayList<Peticion> peticionesb = new ArrayList<>();

	/**
	 * Clase interna Peticion que representa una petición de transferencia o alerta.
	 */
	private class Peticion {
		private String idPrivado;
		private int dinero;
		private Monitor.Cond cond;

		/**
		 * Constructor de la clase Peticion.
		 * 
		 * @param idPrivado La identidad privada que realiza la petición.
		 * @param dinero    La cantidad de dinero involucrada en la petición.
		 */
		Peticion(String idPrivado, int dinero) {
			this.dinero = dinero;
			this.idPrivado = idPrivado;
			this.cond = mutex.newCond();
		}
	}

	/**
	 * Método para crear una nueva cuenta en la blockchain.
	 * 
	 * @param idPrivado La identidad privada de la nueva cuenta.
	 * @param idPublico La identidad pública de la nueva cuenta.
	 * @param v         El saldo inicial de la nueva cuenta.
	 */
	public void crear(String idPrivado, String idPublico, int v) {
		mutex.enter();
		if (v < 0 || cuentas.containsKey(idPrivado) || identidades.containsKey(idPublico)) {
			mutex.leave();
			throw new IllegalArgumentException();
		}
		cuentas.put(idPrivado, v);
		identidades.put(idPublico, idPrivado);
		mutex.leave();
	}

	/**
	 * Método para realizar una transferencia de dinero entre cuentas.
	 * 
	 * @param idPrivado        La identidad privada de la cuenta origen.
	 * @param idPublicoDestino La identidad pública de la cuenta destino.
	 * @param valor            La cantidad de dinero a transferir.
	 */
	public void transferir(String idPrivado, String idPublicoDestino, int valor) {
		mutex.enter();
		if (valor <= 0 || !cuentas.containsKey(idPrivado) || !identidades.containsKey(idPublicoDestino)
				|| idPrivado.equals(identidades.get(idPublicoDestino))) {
			mutex.leave();
			throw new IllegalArgumentException();
		}
		if (hayTransaccionPendiente(idPrivado)) {
			Peticion peticion = new Peticion(idPrivado, valor);
			peticiones.add(peticion);
			peticion.cond.await();
		}
		if (cuentas.get(idPrivado) < valor) {
			Peticion peticion = new Peticion(idPrivado, valor);
			peticiones.add(peticion);
			peticion.cond.await();
		}
		cuentas.put(idPrivado, cuentas.get(idPrivado) - valor);
		cuentas.put(identidades.get(idPublicoDestino), cuentas.get(identidades.get(idPublicoDestino)) + valor);
		desbloquearCuentas();
		mutex.leave();
	}

	/**
	 * Método para comprobar si hay una transacción pendiente para una cuenta.
	 * 
	 * @param idPrivado La identidad privada de la cuenta a comprobar.
	 * @return true si hay una transacción pendiente, false en caso contrario.
	 */
	private boolean hayTransaccionPendiente(String idPrivado) {
		for (Peticion peticion : peticiones) {
			if (peticion.idPrivado.equals(idPrivado)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Método para obtener el saldo disponible de una cuenta.
	 * 
	 * @param idPrivado La identidad privada de la cuenta.
	 * @return El saldo disponible de la cuenta.
	 */
	public int disponible(String idPrivado) {
		mutex.enter();
		if (!cuentas.containsKey(idPrivado)) {
			mutex.leave();
			throw new IllegalArgumentException();
		}
		int disponible = cuentas.get(idPrivado);
		mutex.leave();
		return disponible;
	}

	/**
	 * Método para alertar cuando el saldo de una cuenta supera un valor máximo.
	 * 
	 * @param idPrivado La identidad privada de la cuenta.
	 * @param m         El valor máximo de saldo.
	 */
	public void alertarMax(String idPrivado, int m) {
		mutex.enter();
		if (!cuentas.containsKey(idPrivado) || m < 0) {
			mutex.leave();
			throw new IllegalArgumentException();
		}
		if (cuentas.get(idPrivado) <= m) {
			Peticion peticion = new Peticion(idPrivado, m);
			peticionesb.add(peticion);
			peticion.cond.await();
		}
		desbloquearCuentas();
		mutex.leave();
	}

	/**
	 * Método para desbloquear las cuentas que tienen peticiones pendientes y ya
	 * pueden ser procesadas.
	 */
	private void desbloquearCuentas() {
		boolean cambio = false;
		String solicitante = "";
		for (int i = 0; i < peticiones.size() && !cambio; i++) {
			Peticion peticion = peticiones.get(i);
			if (solicitante.equals(peticion.idPrivado)) {
				continue;
			}
			if (cuentas.get(peticion.idPrivado) >= peticion.dinero) {
				peticion.cond.signal();
				peticiones.remove(i);
				cambio = true;
				break;
			}
			solicitante = peticion.idPrivado;
		}
		for (int i = 0; i < peticionesb.size() && !cambio; i++) {
			Peticion peticion = peticionesb.get(i);
			if (cuentas.get(peticion.idPrivado) > peticion.dinero) {
				peticion.cond.signal();
				peticionesb.remove(i);
				cambio = true;
				break;
			}
		}
	}
}