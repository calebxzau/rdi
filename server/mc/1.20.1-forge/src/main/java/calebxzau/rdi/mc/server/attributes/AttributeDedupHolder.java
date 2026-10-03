package calebxzau.rdi.mc.server.attributes;

import calebxzau.rdi.mc.v20.server.attributes.AttributeDedupState;

/** Mixed into Connection: the attribute snapshots this connection's client already holds. */
public interface AttributeDedupHolder {
    AttributeDedupState rdi$attributeDedup();
}
