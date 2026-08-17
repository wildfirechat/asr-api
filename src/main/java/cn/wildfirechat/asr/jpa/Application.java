package cn.wildfirechat.asr.jpa;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;

@Entity
@Table(name = "application")
public class Application {
    @Id
    @Column(name = "id", length = 64)
    public String appId;

    //密钥
    @Column(length = 64)
    public String secret;

    @Column(length = 1024)
    public String extra;
}
